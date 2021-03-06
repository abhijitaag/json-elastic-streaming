package com.abhijitagarwal

import com.typesafe.config.ConfigFactory
import org.apache.spark.SparkConf
import org.apache.spark.streaming.{Seconds, StreamingContext}
import spray.json.JsonParser
import json.Record
import json.RecordProtocol._
import org.elasticsearch.spark.rdd.EsSpark

import scala.collection.immutable.Stream.Empty

/**
 * Created by abhijitagarwal on 08/08/15 at 22:07.
 */
object NetcatToElastic {

  val config = ConfigFactory.load()
  val lastNUrls = config.getInt("last-n-urls")
  val streamingBatchIntervalSec = config.getInt("streaming-batch-interval-sec")

  def main(args: Array[String]): Unit = {

    // Create context with streaming batch interval
    val sparkConf = new SparkConf()
      .setAppName("NetcatToElastic")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("es.nodes", config.getString("elasticsearch-broker"))
      .set("es.index.auto.create", "true")

    val ssc = new StreamingContext(sparkConf, Seconds(streamingBatchIntervalSec))

    // Create a DStream that will connect to hostname:port, like localhost:9999
    val lines = ssc.socketTextStream(config.getString("netcat.host"), config.getInt("netcat.port"))

    // Parse JSON into a Record case class
    val records = lines.map { line => JsonParser(line.toLowerCase).convertTo[Record] }

    // Make a list of all records per id and sorted by time
    val recordsAll = records.map { record => (record.id, Stream(record)) }.reduceByKey(mergeRecords)

    // Get the last N URLs per id
    case class EsRecord(id: Int, url: Seq[String])
    val idUrlsLastN = recordsAll.map {
      case (id, idRecords) => EsRecord(id, idRecords.map(_.url).takeRight(lastNUrls))
    }

    // Save to Elastic Search
    idUrlsLastN.foreachRDD { rdd =>
      EsSpark.saveToEs(rdd, "spark/docs", Map("es.mapping.id" -> "id"))
    }

    // Start streaming
    ssc.start()
    ssc.awaitTermination()

  }

  // This method merges two time sorted record streams into one time sorted record stream
  // src: http://codereview.stackexchange.com/questions/21575/merge-sort-in-scala
  def mergeRecords(first: Stream[Record], second: Stream[Record]): Stream[Record] = {
    (first, second) match {
      case (x #:: xs, ys@(y #:: _)) if x.time <= y.time => x #:: mergeRecords(xs, ys)
      case (xs, y #:: ys) => y #:: mergeRecords(xs, ys)
      case (xs, Empty) => xs
      case (Empty, ys) => ys
    }
  }

}
