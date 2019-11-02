case class Node(
    id: Int,
    v: NodeValue
) {
  lazy val toNodeView = v match {
    case NodeApp(l, r) => NodeView(id, v.toString(), Some(l), Some(r))
    case _             => NodeView(id, v.toString(), None, None)
  }
}

trait NodeValue

case class NodeInt(i: Int) extends NodeValue {
  override def toString = s"$i"
}

case class NodeApp(
    l: Int,
    r: Int
) extends NodeValue {
  override def toString = s"@"
}

case class NodeFun(
    name: String,
    k: Int,
    c: List[Instruction]
) extends NodeValue {
  override def toString = s"$name"
}

case object NodeHole extends NodeValue {
  override def toString = "_"
}

case class Machine(
    counter: Int,
    commandsLimit: Int,
    stack: List[Int],
    graph: Map[Int, Node],
    commands: List[Instruction],
    dump: List[(List[Int], List[Instruction])],
    funcs: Map[String, NodeFun]
) {
  def getID(k: Int): Node = graph.get(k).get
}

case object MySystem {
  def run(instrs: List[Instruction]): DiffWithErr =
    check(instrs) match {
      case s @ Some(x) => DiffWithErr(Nil, s)
      case None =>
        startJob(Machine(0, 10000, Nil, Map.empty, instrs, Nil, Map.empty))
    }

  def initDiff(m: Machine, oldM: Machine): Diff = {
    Diff(
      oldM.commands.head.toString,
      GraphDiff(Nil, Nil),
      GraphDiff(Nil, Nil),
      m.stack.map(id => m.getID(id)).map(_.toNodeView)
    )
  }

  // TODO(a.eremeev) Тут нужно проверить, что все BEGIN, END, GLOBALSTART расставлены правильно
  def check(instrs: List[Instruction]): Option[String] = None

  def startJob(m: Machine): DiffWithErr = {
    val main = m.commands.tail.takeWhile(_ != End)
    val funcs = m.commands.tail
      .dropWhile(_ != End)
      .drop(1)
      .foldLeft[List[(String, NodeFun)]](Nil)(
        (z, x) =>
          x match {
            case GlobStart(name, k) => z :+ (name, (NodeFun(name, k, Nil)))
            case _ =>
              z.take(z.length - 1) :+ z.last
                .copy(_2 = z.last._2.copy(c = z.last._2.c :+ x))
          }
      )
      .toMap
    Recursive(MyMonad.unit(m.copy(commands = main, funcs = funcs))).mo match {
      case (diffs, Left(str)) => DiffWithErr(diffs, Some(str))
      case (diffs, _)         => DiffWithErr(diffs, None)
    }
  }
  def Recursive(m: MyMonad[Machine]): MyMonad[Machine] = {
    m.flatMap(
        m =>
          if (m.commandsLimit <= 0) {
            MyMonad.unit(m.copy(commandsLimit = m.commandsLimit - 1))
          } else {
            MyMonad[Machine](Nil, Left("commands limit exceeded"))
          }
      )
      .flatMap(
        machine =>
          machine.commands match {
            case Nil => MyMonad.unit(machine)
            case _ => {
              val f = machine.commands.head match {
                case Push(k)       => push(k)
                case PushInt(n)    => pushInt(n)
                case PushGlobal(s) => pushGlob(s)
                case Pop(k)        => pop(k)
                case Slide(k)      => slide(k)
                case Update(k)     => update(k)
                case Alloc(k)      => alloc(k)
                case Add           => ensureTwoIntsFromStack(_ + _)
                case Mul           => ensureTwoIntsFromStack(_ * _)
                case Div           => ensureTwoIntsFromStack(_ - _)
                case Sub           => ensureTwoIntsFromStack(_ / _)
                case MkAp          => mkAp()
                case Eval          => eval()
                case Unwind        => unwind()
                case Ret           => ret()
                case _             => ???
              }
              Recursive(f(machine))
            }
          }
      )
  }
  def push(k: Int): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      if (m.stack.length < k + 1) {
        MyMonad(
          Nil,
          Left(
            s"not enough values in stack for ${m.commands.head}, have only " + m.stack.length + " values, want " + (k + 1)
          )
        )
      } else {
        val newM = m.copy(
          stack = m.stack.drop(k).head :: m.stack,
          commands = m.commands.drop(1)
        )
        MyMonad(initDiff(newM, m) :: Nil, Right(newM))
      }
    }
  }
  def pushInt(k: Int): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      val n = Node(m.counter, NodeInt(k))
      val newM = m.copy(
        counter = m.counter + 1,
        stack = m.counter :: m.stack,
        graph = m.graph + (n.id -> n),
        commands = m.commands.drop(1)
      )
      val diff =
        initDiff(newM, m).copy(add = GraphDiff(n.toNodeView :: Nil, Nil))
      MyMonad(diff :: Nil, Right(newM))
    }
  }
  def pushGlob(k: String): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      m.funcs
        .get(k)
        .map(Node(m.counter, _))
        .map((n: Node) => {
          val newM = m.copy(
            counter = m.counter + 1,
            stack = m.counter :: m.stack,
            graph = m.graph + (n.id -> n),
            commands = m.commands.drop(1)
          )
          val diff =
            initDiff(newM, m).copy(add = GraphDiff(n.toNodeView :: Nil, Nil))
          MyMonad(diff :: Nil, Right(newM))
        })
        .getOrElse(MyMonad(Nil, Left(s"Not Found Function $k")))
    }
  }
  def pop(k: Int): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      if (m.stack.length < k + 1) {
        MyMonad(
          Nil,
          Left(
            s"not enough values in stack for ${m.commands.head}, have only " + m.stack.length + " values, want " + k
          )
        )
      } else {
        val newM =
          m.copy(stack = m.stack.drop(k), commands = m.commands.drop(1))
        val diff = initDiff(newM, m)
        MyMonad(diff :: Nil, Right(newM))
      }
    }
  }
  def slide(k: Int): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      if (m.stack.length < k + 1) {
        MyMonad(
          Nil,
          Left(
            s"not enough values in stack for ${m.commands.head}, have only ${m.stack.length} values, want ${k + 1}"
          )
        )
      } else {
        val newM = m.copy(
          stack = m.stack.head :: m.stack.drop(k + 1),
          commands = m.commands.drop(1)
        )
        val diff = initDiff(newM, m)
        MyMonad(diff :: Nil, Right(newM))
      }
    }
  }
  //TODO UINT
  def update(k: Int): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      if (m.stack.length < k + 1) {
        MyMonad(
          Nil,
          Left(
            s"not enough values in stack for ${m.commands.head}, have only ${m.stack.length} values, want ${k + 1}"
          )
        )
      } else if (m.stack.length == 0) {
        MyMonad(Nil, Left("UPDATE 0 isn't permitted"))
      } else {
        val kid = m.stack.drop(k).head
        val newM = m.copy(
          stack = m.stack.tail,
          graph = m.graph + (kid -> m.getID(m.stack.head).copy(id = kid)),
          commands = m.commands.drop(1)
        )
        val diff = initDiff(newM, m).copy(
          add = GraphDiff(newM.getID(kid).toNodeView :: Nil, Nil),
          remove = GraphDiff(m.getID(kid).toNodeView :: Nil, Nil)
        )
        MyMonad(diff :: Nil, Right(newM))
      }
    }
  }
  def alloc(k: Int): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      val n = Node(m.counter, NodeInt(k))
      val ids = (m.counter until (m.counter + k)).toList
        .foldLeft[List[Int]](Nil)((z, x) => x :: z)
      val mpairs = ids.map(x => x -> Node(x, NodeHole))
      val newM = m.copy(
        counter = m.counter + k,
        stack = ids ++ m.stack,
        graph = m.graph ++ mpairs.toMap,
        commands = m.commands.drop(1)
      )
      val diff = initDiff(newM, m).copy(
        add = GraphDiff(mpairs.unzip._2.map(_.toNodeView), Nil)
      )
      MyMonad(diff :: Nil, Right(newM))
    }
  }
  def mkAp(): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      if (m.stack.length < 2) {
        MyMonad(
          Nil,
          Left(
            s"not enough values in stack for ${m.commands.head}, have only ${m.stack.length} values, want 2"
          )
        )
      } else {
        val ap = Node(m.counter, NodeApp(m.stack.head, m.stack.tail.head))
        val newM = m.copy(
          stack = m.counter :: m.stack.drop(2),
          counter = m.counter + 1,
          graph = m.graph + (ap.id -> ap),
          commands = m.commands.drop(1)
        )
        val diff =
          initDiff(newM, m).copy(add = GraphDiff(ap.toNodeView :: Nil, Nil))
        MyMonad(diff :: Nil, Right(newM))
      }
    }
  }
  // TODO(a.eremeev) make function that can check that there is enough values in stack
  def ensureTwoIntsFromStack(
      f: (Int, Int) => Int
  ): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      if (m.stack.length < 2) {
        MyMonad(
          Nil,
          Left(
            s"not enough values in stack for ${m.commands.head}, have only ${m.stack.length} values, want 2"
          )
        )
      } else {
        m.stack.take(2).map(m.getID(_).v) match {
          case NodeInt(k1) :: NodeInt(k2) :: Nil => {
            val newN = Node(m.counter, NodeInt(f(k1, k2)))
            val newM = m.copy(
              counter = m.counter + 1,
              stack = m.counter :: (m.stack.drop(2)),
              graph = m.graph + (m.counter -> newN),
              commands = m.commands.drop(1)
            )
            val diff = initDiff(newM, m).copy(
              add = GraphDiff(newN.toNodeView :: Nil, Nil)
            )
            MyMonad(diff :: Nil, Right(newM))
          }
          case _ => {
            val err = s"Bad Nodes on the top of stack want Int Nodes, Have ${m
              .getID(m.stack.head)
              .v} and ${m.getID(m.stack.tail.head).v}"
            MyMonad(Nil, Left(err))
          }
        }
      }
    }
  }
  def eval(): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      if (m.stack.length < 1) {
        MyMonad(
          Nil,
          Left(
            s"not enough values in stack for ${m.commands.head}, have only ${m.stack.length} values, want 1"
          )
        )
      } else {
        val headN = m.getID(m.stack.head)
        val newM = headN.v match {
          case _: NodeApp =>
            m.copy(
              stack = m.stack.head :: Nil,
              commands = Unwind :: Nil,
              dump = (m.stack.tail, m.commands.tail) :: m.dump
            )
          case NodeFun(_, 0, coms) =>
            m.copy(
              stack = m.stack.head :: Nil,
              commands = coms,
              dump = (m.stack.tail, m.commands.tail) :: m.dump
            )
          case _ => m.copy(commands = m.commands.drop(1))
        }
        MyMonad(initDiff(newM, m) :: Nil, Right(newM))
      }
    }
  }
  def unwind(): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      if (m.stack.length < 1) {
        MyMonad(
          Nil,
          Left(
            s"not enough values in stack for ${m.commands.head}, have only ${m.stack.length} values, want 1"
          )
        )
      } else {
        val headN = m.getID(m.stack.head)
        val newM = headN.v match {
          case NodeApp(v1, v2) => m.copy(stack = v1 :: m.stack)
          case NodeFun(_, k, coms) => {
            if (m.stack.length + 1 < k) {
              m.copy(
                stack = m.stack.last :: m.dump.head._1,
                commands = m.dump.head._2,
                dump = m.dump.tail
              )
            } else {
              m.copy(
                stack = m.stack.tail
                  .take(k)
                  .map(m.getID(_).v)
                  .map({ case NodeApp(_, n) => n }) ++ (m.stack.head :: m.stack
                  .drop(k + 1)),
                commands = coms
              )
            }
          }
          case _ =>
            m.copy(
              stack = m.stack.head :: (m.dump.head._1),
              commands = m.dump.head._2,
              dump = m.dump.tail
            )
        }
        MyMonad(initDiff(newM, m) :: Nil, Right(newM))
      }
    }
  }
  def ret(): Machine => MyMonad[Machine] = { (m: Machine) =>
    {
      // TODO(a.eremeev) check that enough values in commands
      if (m.stack.length < 1) {
        MyMonad(
          Nil,
          Left(
            s"not enough values in stack for ${m.commands.head}, have only ${m.stack.length} values, want 1"
          )
        )
      } else if (m.dump.length < 1) {
        MyMonad(
          Nil,
          Left(
            s"not enough values in dump for ${m.commands.head}, have only ${m.dump.length} values, want 1"
          )
        )
      } else {
        val newM = m.copy(
          stack = m.stack.last :: m.dump.head._1,
          dump = m.dump.tail,
          commands = m.dump.head._2
        )
        MyMonad(initDiff(newM, m) :: Nil, Right(newM))
      }
    }
  }
}

case class MyMonad[T](mo: (List[Diff], Either[String, T])) {
  def flatMap[K](f: T => MyMonad[K]): MyMonad[K] = {
    MyMonad(mo match {
      case v @ (ls, Right(t)) =>
        f(t).mo match {
          case (l, Right(k)) => (ls ++ l, Right(k))
          case (l, left)     => (ls ++ l, left)
        }
      case (l, Left(mo)) => (l, Left(mo))
    })
  }
}

object MyMonad {
  def unit[T](t: T): MyMonad[T] = {
    MyMonad(Nil, Right(t))
  }
}
