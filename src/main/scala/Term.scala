trait Term

case class Var(name: String) extends Term {
  override def toString(): String = name
}

case class Appl(first: Term, second: Term) extends Term {
  override def toString(): String =
    "(" + first + " " + second + ")"
}

case class Abstr(variable: Var, body: Term) extends Term {
  override def toString(): String =
    "λ" + variable + "." + body + ""
}

trait BuiltIn extends Term with SCTerm

case class IntTerm(value: Int) extends BuiltIn {
  override def toString = value.toString
}

case object IntSum extends BuiltIn {
  override def toString = "SUM"
}

case object IntSub extends BuiltIn {
  override def toString = "SUB"
}

case object IntDiv extends BuiltIn {
  override def toString = "DIV"
}

case object IntMult extends BuiltIn {
  override def toString = "MUL"
}

case object IntGte extends BuiltIn {
  override def toString = "GTE"
}

case object IFClause extends BuiltIn {
  override def toString = "IF"
}

case class BoolTerm(value: Boolean) extends BuiltIn {
  override def toString = value.toString.toUpperCase
}
