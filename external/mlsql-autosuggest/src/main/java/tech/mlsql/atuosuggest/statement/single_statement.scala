package tech.mlsql.atuosuggest.statement

import org.antlr.v4.runtime.Token
import org.apache.spark.sql.catalyst.parser.SqlBaseLexer
import tech.mlsql.atuosuggest.AttributeExtractor
import tech.mlsql.atuosuggest.dsl.{Food, TokenMatcher, TokenTypeWrapper}
import tech.mlsql.atuosuggest.meta.MetaTableKey

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class MetaTableKeyWrapper(metaTableKey: MetaTableKey, aliasName: Option[String])

/**
 * the atom query statement is only contains:
 * select,from,groupby,where,join limit
 * Notice that we do not make sure the sql is right
 *
 */
class SingleStatementAST(val selectSuggester: SelectSuggester, var start: Int, var stop: Int, var parent: SingleStatementAST) {
  val children = ArrayBuffer[SingleStatementAST]()

  def isLeaf = {
    children.length == 0
  }

  def name(tokens: List[Token]): Option[String] = {
    if (parent == null) None
    else Option(tokens.slice(start, stop).last.getText)
  }

  def tables(tokens: List[Token]) = {
    if (isLeaf) {
      //collect table first
      // T__3 == .
      // extract from `from`
      val fromTables = new TableExtractor(selectSuggester.context, this, tokens)
      val fromStart = TokenMatcher(tokens.slice(0, stop), start).asStart(Food(None, SqlBaseLexer.FROM), 1).start
      val tempTables = fromTables.iterate(fromStart, tokens.size)
      //extract from `join`
      val joinTables = new TableExtractor(selectSuggester.context, ast = this, tokens)
      val joinStart = TokenMatcher(tokens.slice(0, stop), start).asStart(Food(None, SqlBaseLexer.JOIN), offset = 1).start
      tempTables ++ joinTables.iterate(joinStart, tokens.size)

    } else {
      children.map(_.name(tokens).get).map { name =>
        MetaTableKeyWrapper(MetaTableKey(None, None, null), Option(name))
      }.toList

    }
  }

  def level = {
    var count = 0
    var temp = this.parent
    while (temp != null) {
      temp = temp.parent
      count += 1
    }
    count
  }


  def output(tokens: List[Token]): List[String] = {
    val selectStart = TokenMatcher(tokens.slice(0, stop), start).asStart(Food(None, SqlBaseLexer.SELECT), 1).start
    val extractor = new AttributeExtractor(selectSuggester.context, this, tokens)
    extractor.iterate(selectStart, tokens.size)
  }

  def visitDown(level: Int)(rule: PartialFunction[(SingleStatementAST, Int), Unit]): Unit = {
    rule.apply((this, level))
    this.children.map(_.visitDown(level + 1)(rule))
  }

  def visitUp(level: Int)(rule: PartialFunction[(SingleStatementAST, Int), Unit]): Unit = {
    this.children.map(_.visitUp(level + 1)(rule))
    rule.apply((this, level))
  }

  def fastEquals(other: SingleStatementAST): Boolean = {
    this.eq(other) || this == other
  }

  def printAsStr(_tokens: List[Token], _level: Int): String = {
    val tokens = _tokens.slice(start, stop)
    val stringBuilder = new mutable.StringBuilder()
    var count = 1
    stringBuilder.append(tokens.map { item =>
      count += 1
      val suffix = if (count % 20 == 0) "\n" else ""
      item.getText + suffix
    }.mkString(" "))
    stringBuilder.append("\n")
    stringBuilder.append("\n")
    stringBuilder.append("\n")
    children.zipWithIndex.foreach { case (item, index) => stringBuilder.append("=" * (_level + 1) + ">" + item.printAsStr(_tokens, _level + 1)) }
    stringBuilder.toString()
  }
}


object SingleStatementAST {

  def matchTableAlias(tokens: List[Token], start: Int) = {
    tokens(start)
  }

  def build(selectSuggester: SelectSuggester, tokens: List[Token]) = {
    _build(selectSuggester, tokens, 0, tokens.size, false)
  }

  def _build(selectSuggester: SelectSuggester, tokens: List[Token], start: Int, stop: Int, isSub: Boolean = false): SingleStatementAST = {
    val ROOT = new SingleStatementAST(selectSuggester, start, stop, null)
    // context start: ( select
    // context end: )

    var bracketStart = 0
    var jumpIndex = -1
    for (index <- (start until stop) if index >= jumpIndex) {
      val token = tokens(index)
      if (token.getType == TokenTypeWrapper.LEFT_BRACKET && index < stop - 1 && tokens(index + 1).getType == SqlBaseLexer.SELECT) {
//        println(s"enter: ${tokens.slice(index, index + 5).map(_.getText).mkString(" ")}")
        val item = SingleStatementAST._build(selectSuggester, tokens, index + 1, stop, true)
        jumpIndex = item.stop
        ROOT.children += item
        item.parent = ROOT

      } else {
        if (isSub) {
          if (token.getType == TokenTypeWrapper.LEFT_BRACKET) {
            bracketStart += 1
          }
          if (token.getType == TokenTypeWrapper.RIGHT_BRACKET && bracketStart != 0) {
            bracketStart -= 1
          }
          else if (token.getType == TokenTypeWrapper.RIGHT_BRACKET && bracketStart == 0) {
            // check the alias
            val matcher = TokenMatcher(tokens, index + 1).eat(Food(None, SqlBaseLexer.AS)).optional.eat(Food(None, SqlBaseLexer.IDENTIFIER)).build
            val stepSize = if (matcher.isSuccess) matcher.get else index
            ROOT.start = start
            ROOT.stop = stepSize
//            println(s"out: ${tokens.slice(stepSize - 5, stepSize).map(_.getText).mkString(" ")}")
            return ROOT
          }

        } else {
          // do nothing
        }
      }

    }
    ROOT
  }
}
