import org.mixql.engine.sqlite.local.SQLightJDBC
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.mixql.core.context.gtype

class MixqlEngineSqliteTest extends AnyFlatSpec with BeforeAndAfter :
  var context: SQLightJDBC = null
  val identity = "MixqlEngineSqliteTest"

  before {
    context = SQLightJDBC(identity)
  }

  def execute(code: String): gtype.Type =
    context.execute(code)

  after {
    context.close()
    SQLightJDBC.c = null
  }

class MixQlEngineSqliteTitanicDbTest extends AnyFlatSpec with BeforeAndAfter :
  var context: SQLightJDBC = null
  val identity = "MixQlEngineSqliteTitanicDbTest"

  before {
    context = SQLightJDBC(identity, Some("mixql.org.engine.sqlight.titanic-db.path"))
  }

  def execute(code: String): gtype.Type =
    context.execute(code)

  after {
    context.close()
    SQLightJDBC.c = null
  }

class MixQlEngineSqliteSakilaDbTest extends AnyFlatSpec with BeforeAndAfter :
  var context: SQLightJDBC = null
  val identity = "MixQlEngineSqliteSakilaDbTest"

  before {
    context = SQLightJDBC(identity, Some("mixql.org.engine.sqlight.sakila-db.path"))
  }

  def execute(code: String): gtype.Type =
    context.execute(code)

  after {
    context.close()
    SQLightJDBC.c = null
  }


