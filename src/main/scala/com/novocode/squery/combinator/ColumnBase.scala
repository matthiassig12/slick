package com.novocode.squery.combinator

import com.novocode.squery.SQueryException
import com.novocode.squery.combinator.basic.BasicProfile
import com.novocode.squery.session.{PositionedResult, PositionedParameters}

/**
 * Common base trait for columns, tables and projections (but not unions and joins).
 */
trait ColumnBaseU extends Node with WithOp {
  type Value

  override def nodeDelegate: Node = if(op eq null) this else op.nodeDelegate

  def getResult(profile: BasicProfile, rs: PositionedResult): Value
  def setParameter(profile: BasicProfile, ps: PositionedParameters, value: Option[Value]): Unit
}

trait ColumnBase[T] extends ColumnBaseU { final type Value = T }

/**
 * Base trait for columns.
 */
trait ColumnU extends ColumnBaseU {
  val typeMapper: TypeMapper[Value]
  def getResult(profile: BasicProfile, rs: PositionedResult): Value = typeMapper(profile).nextValue(rs)
  final def setParameter(profile: BasicProfile, ps: PositionedParameters, value: Option[Value]): Unit = typeMapper(profile).setOption(value, ps)
  def orElse(n: =>Value): Column[Value] = new WrappedColumn(this, typeMapper) {
    override def getResult(profile: BasicProfile, rs: PositionedResult): Value = typeMapper(profile).nextValueOrElse(n, rs)
  }
  final def orFail = orElse { throw new SQueryException("Read NULL value for column "+this) }
  def ? : Column[Option[Value]] = new WrappedColumn(this, typeMapper.createOptionTypeMapper)
  final def ~[U <: Column[_]](b: U) = new Projection2[this.type, U](this, b)

  // Functions which don't need an OptionMapper
  def in(e: Query[Column[_]]) = Operator.In(Node(this), Node(e))
  def notIn(e: Query[Column[_]]) = Operator.Not(Node(Operator.In(Node(this), Node(e))))
  def count = Operator.Count(Node(this))
  def avg = mapOp(Operator.Avg(_))
  def min = mapOp(Operator.Min(_))
  def max = mapOp(Operator.Max(_))
  def isNull = Operator.Is(Node(this), ConstColumn.NULL)
  def isNotNull = Operator.Not(Node(Operator.Is(Node(this), ConstColumn.NULL)))
  def countDistinct = Operator.CountDistinct(Node(this))

  def asc = new Ordering.Asc(Node(this))
  def desc = new Ordering.Desc(Node(this))
}

trait Column[T] extends ColumnBase[T] with ColumnU

/**
 * A column with a constant value which is inserted into an SQL statement as a literal.
 */
case class ConstColumn[T](value: T)(implicit val typeMapper: TypeMapper[T]) extends Column[T] {
  def nodeChildren = Nil
  override def toString = value match {
    case null => "ConstColumn null"
    case a: AnyRef => "ConstColumn["+a.getClass.getName+"] "+a
    case _ => "ConstColumn "+value
  }
  def bind = new BindColumn(value)
}

object ConstColumn {
  def NULL = new ConstColumn[Null](null)(TypeMapper.NullTypeMapper)
}

/**
 * A column with a constant value which gets turned into a bind variable.
 */
case class BindColumn[T](value: T)(implicit val typeMapper: TypeMapper[T]) extends Column[T] {
  def nodeChildren = Nil
  override def toString = value match {
    case null => "BindColumn null"
    case a: AnyRef => "BindColumn["+a.getClass.getName+"] "+a
    case _ => "BindColumn "+value
  }
}

/**
 * A parameter from a QueryTemplate which gets turned into a bind variable.
 */
case class ParameterColumn[T](idx: Int, typeMapper: TypeMapper[T]) extends Column[T] {
  def nodeChildren = Nil
  override def toString = "ParameterColumn "+idx
}

/**
 * A column which gets created as the result of applying an operator.
 */
abstract class OperatorColumn[T](implicit val typeMapper: TypeMapper[T]) extends Column[T] {
  protected[this] val leftOperand: Node = Node(this)
}

/**
 * A WrappedColumn can be used to change a column's nullValue.
 */
class WrappedColumn[T](parent: ColumnU, val typeMapper: TypeMapper[T]) extends Column[T] {
  override def nodeDelegate = if(op eq null) Node(parent) else op.nodeDelegate
  def nodeChildren = nodeDelegate :: Nil
}

/**
 * A column which is part of a Table.
 */
class NamedColumn[T](val table: Node, val name: String, val typeMapper: TypeMapper[T], val options: ColumnOption[T]*)
extends Column[T] {
  def nodeChildren = table :: Nil
  override def toString = "NamedColumn " + name
  override def nodeNamedChildren = (table, "table") :: Nil
}

object NamedColumn {
  def unapply[T](n: NamedColumn[T]) = Some((n.table, n.name, n.typeMapper, n.options))
}
