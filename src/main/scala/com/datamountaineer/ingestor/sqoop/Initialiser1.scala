package com.datamountaineer.ingestor.sqoop

import java.io.IOException
import java.sql.{Connection, DriverManager, ResultSet}

import com.datamountaineer.ingestor.conf.Configuration
import com.datamountaineer.ingestor.models.JobMetaStorage
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.collection.mutable


object Initialiser  extends Configuration {
  val log = LoggerFactory.getLogger("Initialiser")
  /*
  * WHAT TO REPLACE THIS WITH SLICK!!!!!!
  *
  * */
  val mysql_query = "SELECT DISTINCT " +
    "t.table_name " +
    ", CONCAT_WS(':'" +
    ", 'mysql'" +
    ", @@hostname" +
    ", t.table_schema" +
    ", t.table_name " +
    ", IFNULL(c.column_name, '') " +
    ", '4'" +
    ", IFNULL(c.column_name, '')" +
    ", 0 ) AS input " +
    "FROM information_schema.tables t " +
    "LEFT OUTER JOIN information_schema.columns c " +
    "ON t.table_name = c.table_name " +
    "AND extra LIKE '%auto_increment%'" +
    "WHERE t.table_schema = \"MY_DATABASE\";"

  val netezza_query = "SELECT DISTINCT " +
    "t.tablename AS table_name " +
    ", 'netezza:MY_SERVER:' || t.database || ':' ||  t.tablename || ':' || ISNULL(d.attname, '') || ':8:' || " +
    "ISNULL(d.attname, '') || ':0' AS input " +
    "FROM _v_table t " +
    "LEFT OUTER JOIN _v_table_dist_map d " +
    "ON t.tablename = d.tablename AND t.database = d.database " +
    "LEFT OUTER JOIN _v_relation_column c " +
    "ON d.database = c.database AND t.objid = c.objid AND c.format_type = 'BIGINT'"

  def main(args: Array[String]) {
    if (args == null || args.length < 3) {
      System.out.println( """
                            |Usage: <initialiser db_type server database>
                          """.stripMargin)
      System.exit(0)
    }

    val db_type = args(0).toString
    val server = args(1).toString
    val database = args(2).toString
    initialise(db_type, server, database)
  }

  def initialise(db_type: String, server: String, database: String) = {
    var conn: Option[Connection] = null
    try {
      conn = get_conn(db_type, server, database)

      conn match {
        case None => log.error("Could not connect to database %s on %s".format(database, server))
        case _ => {
          val stmt = conn.get.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
          val query = get_query(db_type).replace("MY_DATABASE", database).replace("MY_SERVER", server)
          val rs: ResultSet = stmt.executeQuery(query)
          val storage = new JobMetaStorage
          storage.open()
          while (rs.next()) {
            val input = rs.getString("input")
            //create sqoop options
            val sqoop_options = new IngestSqoop(input, true).build_sqoop_options()
            //call ingestor to create the
            storage.create(sqoop_options)
          }
        }
      }
    }
    finally {
      if (conn != null) conn.get.close()
    }
  }

  /**
   * Return query for a given db_type
   * @param db_type Type of database
   * */
  def get_query(db_type: String) : String = {
    db_type.toLowerCase match {
      case "mysql" =>
        mysql_query
      case "netezza" =>
        netezza_query
    }
  }

  /**
   * Return a databases connection details if found in application.conf
   *
   * @param db_type The type of database
   * @param server The server hosting the database
   * @param database The database credentials to look up
   * */
  def get_db_conf(db_type: String, server: String, database: String) : Option[DbConfig] = {
    val conf_key = "%s.%s.dbs".format(db_type, server)
    val conf : mutable.Buffer[DbConfig] = config.getConfigList(conf_key) map (new DbConfig(_, db_type, server))
    val db_list = for (db <- conf if db.name.equals(database)) yield db.asInstanceOf[DbConfig]

    if (db_list.size > 1) log.warn("Found more than one database called %s configured.".format(database))
    if (db_list.size == 0) {
      log.error("Did not find database called %s in application.conf.".format(database))
      None
    } else {
      Some(db_list.head)
    }
  }

  /**
  * Returns a connection for given db type
  * @param db_type Type of database e.g. mysql, netezza
  * @param server Server hosting the database
  * @param database Database database to connect to
  * */
  def get_conn(db_type: String, server: String, database: String) : Option[Connection] = {
    val db = get_db_conf(db_type, server, database)
    db match {
      case None => None
      case db_details => {
        val credentials = db.get.get_credentials()
        db_type.toLowerCase match {
          case "mysql" =>
            val conn_str = "jdbc:mysql://" + server + ":3306/" + database
            classOf[com.mysql.jdbc.Driver].newInstance()
            try {
              val conn = DriverManager.getConnection(conn_str, credentials._1, credentials._2)
              Some(conn)
            } catch {
              case e: Exception  =>
                log.error(e.getMessage, new IOException)
                throw e
            }
          case "netezza" =>
            classOf[org.netezza.Driver].newInstance()
            val conn = DriverManager.getConnection("jdbc:netezza://" + server + ":5480/" + database,
              credentials._1, credentials._2)
            Some(conn)
        }
      }
    }
  }
}