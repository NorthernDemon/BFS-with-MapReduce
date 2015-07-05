package it.unitn.bd;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import it.unitn.bd.bfs.Color;
import it.unitn.bd.bfs.Vertex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import scala.Tuple2;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Hello Spark Example
 */
public final class BfsSpark {

    private static final Logger logger = LogManager.getLogger();

    private static final Splitter SPACE = Splitter.on(" ");
    private static final Joiner NEW_LINE = Joiner.on("\n");

    private static final String APP_NAME = ServiceConfiguration.getAppName();
    private static final String IP = ServiceConfiguration.getIp();
    private static final int PORT = ServiceConfiguration.getPort();
    private static final String JAR = ServiceConfiguration.getJar();
    private static final List<String> PROBLEM_FILE = ServiceConfiguration.getProblemFiles();

    public static void main(String[] args) throws Exception {
        String master = "spark://" + IP + ':' + PORT;
        logger.info("Application name: " + APP_NAME);
        logger.info("Problem files path: " + PROBLEM_FILE);
        logger.info("Using JAR file: " + JAR);
        logger.info("Connecting to: " + master);
        SparkConf sparkConf = new SparkConf()
                .setAppName(APP_NAME)
                .setMaster(master);
        JavaSparkContext ctx = new JavaSparkContext(sparkConf);
        ctx.addJar("target/" + JAR + ".jar");
        Stopwatch stopwatch = Stopwatch.createUnstarted();
        for (String problemFile : ServiceConfiguration.getProblemFiles()) {
            logger.info("Problem file: " + problemFile);
            int graphDiameter = transformGraphInputFile(problemFile);

            for (int i = 1; i < graphDiameter; i++) {
                stopwatch.start();

                JavaRDD<String> lines = ctx.textFile(problemFile + "_" + (i - 1));

                JavaPairRDD<Integer, Vertex> mapper = lines.flatMapToPair(new PairFlatMapFunction<String, Integer, Vertex>() {
                    @Override
                    public Iterable<Tuple2<Integer, Vertex>> call(String source) throws Exception {
                        List<Tuple2<Integer, Vertex>> result = new ArrayList<>();

                        // For each GRAY vertex, emit each of the edges as a new vertex (also GRAY)
                        Vertex vertex = new Vertex(source);
                        if (vertex.getColor() == Color.GRAY) {
                            for (int edge : vertex.getEdges()) {
                                Vertex edgeVertex = new Vertex(edge, new HashSet<Integer>(), vertex.getDistance() + 1, Color.GRAY);
                                result.add(new Tuple2<>(edgeVertex.getId(), edgeVertex));
                            }
                            // We're done with this vertex now, color it BLACK
                            vertex.setColor(Color.BLACK);
                        }

                        // No matter what, we emit the input vertex
                        // If the vertex came into this method GRAY, it will be output as BLACK
                        result.add(new Tuple2<>(vertex.getId(), vertex));
                        return result;
                    }
                });

                JavaPairRDD<Integer, Vertex> reducer = mapper.reduceByKey(new Function2<Vertex, Vertex, Vertex>() {
                    public Vertex call(Vertex vertex1, Vertex vertex2) {
                        // Save the minimum distance
                        int distance = vertex1.getDistance() < vertex2.getDistance() ? vertex1.getDistance() : vertex2.getDistance();

                        // One (and only one) copy of the vertex will be the fully expanded
                        // version, which includes the edges
                        Set<Integer> edges = !vertex1.getEdges().isEmpty() ? vertex1.getEdges() : vertex2.getEdges();

                        // Save the darkest color
                        Color color = vertex1.getColor().ordinal() > vertex2.getColor().ordinal() ? vertex1.getColor() : vertex2.getColor();

                        return new Vertex(vertex1.getId(), edges, distance, color);
                    }
                });

                String content = "";
                logger.info("Result of iteration " + i + " / " + graphDiameter);
                for (Tuple2<?, ?> tuple : reducer.collect()) {
                    logger.info(tuple._1() + ": " + tuple._2());
                    content += tuple._2() + "\n";
                }
                Files.write(Paths.get(problemFile + "_" + i), content.getBytes(), StandardOpenOption.CREATE);

                logger.info("Elapsed time ==> " + stopwatch);
                stopwatch.reset();
            }
        }
        ctx.stop();
    }

    /**
     * Transform given undirected graph file from the Algorithm book
     * into appropriate file structure for MapReduce process
     *
     * @param problemFile of the Robert Sedgewick
     * @return diameter of the graph
     * @throws IOException if cannot write to file system
     */
    private static int transformGraphInputFile(String problemFile) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(problemFile), Charset.defaultCharset());

        int vertexCount = Integer.parseInt(lines.get(0));
        Map<Integer, Vertex> vertexes = new HashMap<>(vertexCount);
        vertexes.put(1, new Vertex(1, new HashSet<Integer>(), 0, Color.GRAY));
        for (int i = 2; i <= vertexCount; i++) {
            vertexes.put(i, new Vertex(i, new HashSet<Integer>(), Integer.MAX_VALUE, Color.WHITE));
        }

        for (int i = 2; i < lines.size(); i++) {
            List<String> pair = SPACE.splitToList(lines.get(i));
            int vertex1 = Integer.parseInt(pair.get(0)) + 1;
            int vertex2 = Integer.parseInt(pair.get(1)) + 1;
            vertexes.get(vertex1).addEdge(vertex2);
            vertexes.get(vertex2).addEdge(vertex1);
        }

        Files.write(Paths.get(problemFile + "_0"), NEW_LINE.join(vertexes.values()).getBytes(), StandardOpenOption.CREATE);

        int graphDiameter = 0;
        for (Vertex vertex : vertexes.values()) {
            if (graphDiameter < vertex.getEdges().size()) {
                graphDiameter = vertex.getEdges().size();
            }
        }
        return graphDiameter;
    }
}
