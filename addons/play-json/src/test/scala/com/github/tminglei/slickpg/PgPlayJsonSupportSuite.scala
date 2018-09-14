package com.github.tminglei.slickpg

import java.util.concurrent.Executors

import org.scalatest.FunSuite
import play.api.libs.json._
import slick.jdbc.{GetResult, PostgresProfile}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

class PgPlayJsonSupportSuite extends FunSuite {
  implicit val testExecContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))

  case class JBean(name: String, count: Int)
  object JBean {
    implicit val jbeanFmt = Json.format[JBean]
    implicit val jbeanWrt = Json.writes[JBean]
  }

  trait MyPostgresProfile extends PostgresProfile
                            with PgPlayJsonSupport
                            with array.PgArrayJdbcTypes {
    override val pgjson = "jsonb"

    override val api: API = new API {}

    val plainAPI = new API with PlayJsonPlainImplicits

    ///
    trait API extends super.API with JsonImplicits {
      implicit val strListTypeMapper = new SimpleArrayJdbcType[String]("text").to(_.toList)
      implicit val beanJsonTypeMapper = MappedJdbcType.base[JBean, JsValue](Json.toJson(_), _.as[JBean])
      implicit val jsonArrayTypeMapper =
        new AdvancedArrayJdbcType[JsValue](pgjson,
          (s) => utils.SimpleArrayUtils.fromString[JsValue](Json.parse(_))(s).orNull,
          (v) => utils.SimpleArrayUtils.mkString[JsValue](_.toString())(v)
        ).to(_.toList)
      implicit val beanArrayTypeMapper =
        new AdvancedArrayJdbcType[JBean](pgjson,
          (s) => utils.SimpleArrayUtils.fromString[JBean](Json.parse(_).as[JBean])(s).orNull,
          (v) => utils.SimpleArrayUtils.mkString[JBean](b => Json.stringify(Json.toJson(b)))(v)
        ).to(_.toList)
    }
  }
  object MyPostgresProfile extends MyPostgresProfile

  ///
  import MyPostgresProfile.api._

  val db = Database.forURL(url = utils.dbUrl, driver = "org.postgresql.Driver")

  case class JsonBean(id: Long, json: JsValue, jsons: List[JsValue], jbean: JBean, jbeans: List[JBean])

  class JsonTestTable(tag: Tag) extends Table[JsonBean](tag, "JsonTest2") {
    def id = column[Long]("id", O.AutoInc, O.PrimaryKey)
    def json = column[JsValue]("json", O.Default(Json.parse(""" {"a":"v1","b":2} """)))
    def jsons = column[List[JsValue]]("jsons")
    def jbean = column[JBean]("jbean")
    def jbeans = column[List[JBean]]("jbeans")

    def * = (id, json, jsons, jbean, jbeans) <> (JsonBean.tupled, JsonBean.unapply)
  }
  val JsonTests = TableQuery[JsonTestTable]

  //------------------------------------------------------------------------------

  val testRec1 = JsonBean(33L, Json.parse(""" { "a":101, "b":"aaa", "c":[3,4,5,9] } """), List(Json.parse(""" { "a":101, "b":"", "c":[3,4,5,9] } """)),
    JBean("tt", 3), List(JBean("tt", 3)))
  val testRec2 = JsonBean(35L, Json.parse(""" [ {"title":"hello\nworld","b":2}, {"a":"v5","b":3} ] """), List(Json.parse(""" [ {"a":"v1","b":2}, {"a":"v5","b":3} ] """)),
    JBean("t1", 5), List(JBean("t1", 5)))
  val testRec3 = JsonBean(37L, Json.parse(""" { "field": "PF/00.0.0 (abc.xyz abc os x.x.x)" } """), List(Json.parse(""" { "field": "PF/00.0.0 (abc.xyz abc os x.x.x)" } """)), JBean("tx", 7), Nil)



  test("Play json Lifted support") {
    val json1 = Json.parse(""" {"b":2,"title":"hello\nworld"} """)
    val json2 = Json.parse(""" {"a":"v5","b":3} """)

    // Unicode testing
    // This byte string equal to {"d":"123\u000045\u00006"}
    val unicodeJsonBytes: List[Byte] = List(123, 34, 100, 34, 58, 34, 49, 50, 51, 92, 117, 48, 48, 48, 48, 52, 53, 92, 117, 48, 48, 48, 48, 54, 34, 125)
    val unicodeJsonString = (new String(unicodeJsonBytes.map(_.toChar).toArray))
    val unicodedJson = Json.parse(unicodeJsonString)
    val unicodelessJson = Json.parse(""" { "d":"123456" } """)
    val testRec4 = JsonBean(39L, unicodedJson, Nil, JBean("t2", 9), Nil)

    val withEscapeChar = Json.parse(""" "hello \\n world" """)

    val testRec5 = JsonBean(41L, Json.parse(""" {} """), List(withEscapeChar), JBean("", 0), Nil)

    Await.result(db.run(
      DBIO.seq(
        JsonTests.schema create,
        ///
        JsonTests forceInsertAll List(testRec1, testRec2, testRec3, testRec4, testRec5)
      ).andThen(
        DBIO.seq(
          JsonTests.filter(_.id === testRec5.id.bind).map(_.jsons).result.head.map(
            r => assert(r.head === withEscapeChar)
          ),
          JsonTests.filter(_.id === testRec2.id.bind).map(_.json).result.head.map(
            r => assert(JsArray(List(json1,json2)) === r)
          ),
          JsonTests.filter(_.id === testRec2.id.bind).map(_.jbean).result.head.map(
            r => assert(JBean("t1", 5) === r)
          ),
          JsonTests.to[List].result.map(
            // testRec4 has its \u0000 character stripped
            r => assert(List(testRec1, testRec2, testRec3, testRec4.copy(json = unicodelessJson), testRec5) === r)
          ),
          // ->>/->
          JsonTests.filter(_.json.+>>("a") === "101").map(_.json.+>>("c")).result.head.map(
            r => assert("[3,4,5,9]" === r.replace(" ", ""))
          ),
          JsonTests.filter(_.json.+>>("a") === "101".bind).map(_.json.+>("c")).result.head.map(
            r => assert(JsArray(List(JsNumber(3), JsNumber(4), JsNumber(5), JsNumber(9))) === r)
          ),
          JsonTests.filter(_.id === testRec2.id).map(_.json.~>(1)).result.head.map(
            r => assert(json2 === r)
          ),
          JsonTests.filter(_.id === testRec2.id).map(_.json.~>>(1)).result.head.map(
            r => assert("""{"a":"v5","b":3}""" === r.replace(" ", ""))
          ),
          // #>>/#>
          JsonTests.filter(_.id === testRec1.id).map(_.json.#>(List("c"))).result.head.map(
            r => assert(Json.parse("[3,4,5,9]") === r)
          ),
          JsonTests.filter(_.json.#>>(List("a")) === "101").result.head.map(
            r => assert(testRec1 === r)
          ),
          // {}_array_length
          JsonTests.filter(_.id === testRec2.id).map(_.json.arrayLength).result.head.map(
            r => assert(2 === r)
          ),
          // {}_array_elements
          JsonTests.filter(_.id === testRec2.id).map(_.json.arrayElements).to[List].result.map(
            r => assert(List(json1, json2) === r)
          ),
          JsonTests.filter(_.id === testRec2.id).map(_.json.arrayElements).result.head.map(
            r => assert(json1 === r)
          ),
          // {}_array_elements_text
          JsonTests.filter(_.id === testRec2.id).map(_.json.arrayElementsText).result.head.map(
            r => assert(json1.toString.replace(" ", "") === r.replace(" ", ""))
          ),
          // {}_object_keys
          JsonTests.filter(_.id === testRec1.id).map(_.json.objectKeys).to[List].result.map(
            r => assert(List("a","b","c") === r)
          ),
          JsonTests.filter(_.id === testRec1.id).map(_.json.objectKeys).result.head.map(
            r => assert("a" === r)
          ),
          // \u0000 test
          JsonTests.filter(_.id === testRec4.id).map(_.json).result.head.map(
            r => assert(unicodelessJson === r)
          ),
          // @>
          JsonTests.filter(_.json @> Json.parse(""" {"b":"aaa"} """)).result.head.map(
            r => assert(33L === r.id)
          ),
          JsonTests.filter(_.json @> Json.parse(""" [{"a":"v5"}] """)).result.head.map(
            r => assert(35L === r.id)
          ),
          // <@
          JsonTests.filter(Json.parse(""" {"b":"aaa"} """) <@: _.json).result.head.map(
            r => assert(33L === r.id)
          ),
          // {}_typeof
          JsonTests.filter(_.id === testRec1.id).map(_.json.+>("a").jsonType).result.head.map(
            r => assert("number" === r.toLowerCase)
          ),
          // ?
          JsonTests.filter(_.json ?? "b".bind).to[List].result.map(
            r => assert(List(testRec1) === r)
          ),
          // ?|
          JsonTests.filter(_.json ?| List("a", "c").bind).to[List].result.map(
            r => assert(List(testRec1) === r)
          ),
          // ?&
          JsonTests.filter(_.json ?& List("a", "c").bind).to[List].result.map(
            r => assert(List(testRec1) === r)
          ),
          // ||
          JsonTests.filter(_.id === 33L).map(_.json || Json.parse(""" {"d":"test"} """)).result.head.map(
            r => assert(""" {"a": 101, "b": "aaa", "c": [3, 4, 5, 9], "d": "test"} """.replace(" ", "") === r.toString().replace(" ", ""))
          ),
          // -
          JsonTests.filter(_.id === 33L).map(_.json - "c".bind).result.head.map(
            r => assert(""" {"a": 101, "b": "aaa"} """.replace(" ", "") === r.toString().replace(" ", ""))
          ),
          // #-
          JsonTests.filter(_.id === 33L).map(_.json #- List("c")).result.head.map(
            r => assert(""" {"a": 101, "b": "aaa"} """.replace(" ", "") === r.toString().replace(" ", ""))
          ),
          // #-
          JsonTests.filter(_.id === 33L).map(_.json.set(List("c"), Json.parse(""" [1] """).bind)).result.head.map(
            r => assert(""" {"a": 101, "b": "aaa", "c": [1]} """.replace(" ", "") === r.toString().replace(" ", ""))
          )
        )
      ).andFinally(
        JsonTests.schema drop
      ).transactionally
    ), Duration.Inf)
  }

  //------------------------------------------------------------------------------

  case class JsonBean1(id: Long, json: JsValue)

  test("Json Plain SQL support") {
    import MyPostgresProfile.plainAPI._

    implicit val getJsonBeanResult = GetResult(r => JsonBean1(r.nextLong(), r.nextJson()))

    val b = JsonBean1(34L, Json.parse(""" { "a":101, "b":"aaa", "c":[3,4,5,9] } """))

    Await.result(db.run(
      DBIO.seq(
        sqlu"""create table JsonTest2(
              id int8 not null primary key,
              json #${MyPostgresProfile.pgjson} not null)
          """,
        ///
        sqlu""" insert into JsonTest2 values(${b.id}, ${b.json}) """,
        sql""" select * from JsonTest2 where id = ${b.id} """.as[JsonBean1].head.map(
          r => assert(b === r)
        ),
        ///
        sqlu"drop table if exists JsonTest2 cascade"
      ).transactionally
    ), Duration.Inf)
  }
}
