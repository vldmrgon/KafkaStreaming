import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka010.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.apache.spark.sql.functions.*;

public class KafkaStreamingExample {
    private static final String bootstrapServer = "127.0.0.1:39092";
    private static final String groupId = "test";
    public static final String ticket = "msft";
    public static final int timeFrame = 10_000;

    public static void main(String[] args) {

        SparkConf sConf = new SparkConf().setAppName("example application").setMaster("local[*]");

        JavaSparkContext sc = new JavaSparkContext(sConf);
        SQLContext sqlContext = new SQLContext(sc);
        JavaStreamingContext jsc = new JavaStreamingContext(sc, Durations.seconds(10));

        Map<String, Object> kafkaConsumerParams = new HashMap<>();
        kafkaConsumerParams.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        kafkaConsumerParams.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        kafkaConsumerParams.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        kafkaConsumerParams.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        kafkaConsumerParams.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        kafkaConsumerParams.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        Collection<String> topics = Arrays.asList("stock-updates", "stock-updates-api");

        JavaInputDStream<ConsumerRecord<String, String>> stream =
                KafkaUtils.createDirectStream(jsc, LocationStrategies.PreferConsistent(),
                        ConsumerStrategies.<String, String>Subscribe(topics, kafkaConsumerParams));

        stream.foreachRDD(rdd -> {
            if (!rdd.isEmpty()) {
                OffsetRange[] offsetRanges = ((HasOffsetRanges) rdd.rdd()).offsetRanges();
                JavaRDD<String> stringJavaRDD = (JavaRDD<String>) rdd.map(ConsumerRecord::value);
                Dataset<Row> dataDF = sqlContext.read().json(stringJavaRDD);

                final Dataset<Row> filterDF = dataDF
                        .filter(col("stockTicker").equalTo(ticket))
                        .sort(desc("date"))
                        .limit(timeFrame / 1000);

                System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
                System.out.println("dataDF = " + filterDF.count());

                Dataset<Row> dataWithIsEvenDF = dataDF
                        .withColumn("is_even",
                                when(
                                        col("message_id").mod(lit(2)).equalTo(lit(0)),
                                        lit(true))
                                        .otherwise(lit(false)));

                Dataset<Row> resultDF = dataWithIsEvenDF
                        .withColumn("next_message_id", col("message_id").plus(lit(1)));

                Dataset<String> outputDS = resultDF.toJSON();
                dataDF.show();

                outputDS.toDF()
                        .write()
                        .format("kafka")
                        .option("kafka.bootstrap.servers", "127.0.0.1:9092")
                        .option("topic", "kafka-tst-01")
                        .save();

                JavaRDD<ProducerRecord<String, String>> outputRDD = outputDS
                        .javaRDD()
                        .map(outputRow -> new ProducerRecord<String, String>("send-signal-stocks", outputRow));


                outputRDD.foreachPartition(partition -> {

                    Map<String, Object> kafkaProducerParams = new HashMap<>();
                    kafkaProducerParams.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
                    kafkaProducerParams.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
                    kafkaProducerParams.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

                    KafkaProducer<String, String> producer = new KafkaProducer<String, String>(kafkaProducerParams);

                    while (partition.hasNext()) {
                        producer.send(partition.next());
                    }
                    producer.flush();
                    producer.close();
                });
                ((CanCommitOffsets) stream.inputDStream()).commitAsync(offsetRanges);
            }
        });

        jsc.start();
        try {
            jsc.awaitTermination();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            jsc.close();
            sc.close();
        }
    }
}