package ch.epfl.scala.githubapi

import munit.FunSuite
import sjsonnew.support.scalajson.unsafe.{CompactPrinter, Converter}
import JsonProtocol._
import sjsonnew.shaded.scalajson.ast.unsafe.JObject
import sjsonnew.shaded.scalajson.ast.unsafe.JString
import sjsonnew.shaded.scalajson.ast.unsafe.JField
import sjsonnew.shaded.scalajson.ast.unsafe.JNumber
import sjsonnew.shaded.scalajson.ast.unsafe.JValue

class JsonProtocolTests extends FunSuite {
  test("encode metadata") {
    val metadata = Map("key1" -> JString("value1"), "key2" -> JNumber(1))
    val obtained = Converter.toJson(metadata).get
    val expected = JObject(JField("key1", JString("value1")), JField("key2", JNumber(1)))
    assertEquals(obtained, expected)
  }

  test("decode metadata") {
    val metadata = JObject(JField("key1", JString("value1")), JField("key2", JNumber(1)))
    val obtained = Converter.fromJson[Map[String, JValue]](metadata).get
    val expected = Map("key1" -> JString("value1"), "key2" -> JNumber(1))
    assertEquals(obtained, expected)
  }
}
