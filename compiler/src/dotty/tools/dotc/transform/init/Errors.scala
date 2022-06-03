package dotty.tools
package dotc
package transform
package init

import ast.tpd._
import core._
import util.SourcePosition
import Decorators._, printing.SyntaxHighlighting
import Types._, Symbols._, Contexts._

import scala.collection.mutable

object Errors:
  sealed trait Error:
    def trace: Seq[Tree]
    def show(using Context): String

    def pos(using Context): SourcePosition = trace.last.sourcePos

    def issue(using Context): Unit =
      report.warning(show + stacktrace, this.pos)

    def stacktrace(using Context): String = if trace.isEmpty then "" else " Calling trace:\n" + {
      var lastLineNum = -1
      var lines: mutable.ArrayBuffer[String] = new mutable.ArrayBuffer
      trace.foreach { tree =>
        val pos = tree.sourcePos
        val prefix = "-> "
        val line =
          if pos.source.exists then
            val loc = "[ " + pos.source.file.name + ":" + (pos.line + 1) + " ]"
            val code = SyntaxHighlighting.highlight(pos.lineContent.trim.nn)
            i"$code\t$loc"
          else
            tree.show
        val positionMarkerLine =
          if pos.exists && pos.source.exists then
            positionMarker(pos)
          else ""

        // always use the more precise trace location
        if lastLineNum == pos.line then
          lines.dropRightInPlace(1)

        lines += (prefix + line + "\n" + positionMarkerLine)

        lastLineNum = pos.line
      }
      val sb = new StringBuilder
      for line <- lines do sb.append(line)
      sb.toString
    }

    /** Used to underline source positions in the stack trace
     *  pos.source must exist
     */
    private def positionMarker(pos: SourcePosition): String =
      val trimmed = pos.lineContent.takeWhile(c => c.isWhitespace).length
      val padding = pos.startColumnPadding.substring(trimmed).nn + "   "
      val carets =
        if (pos.startLine == pos.endLine)
          "^" * math.max(1, pos.endColumn - pos.startColumn)
        else "^"

      s"$padding$carets\n"

    override def toString() = this.getClass.getName.nn
  end Error

  /** Access non-initialized field */
  case class AccessNonInit(field: Symbol, trace: Seq[Tree]) extends Error:
    def source: Tree = trace.last
    def show(using Context): String =
      "Access non-initialized " + field.show + "."

    override def pos(using Context): SourcePosition = field.sourcePos

  /** Promote a value under initialization to fully-initialized */
  case class PromoteError(msg: String, trace: Seq[Tree]) extends Error:
    def show(using Context): String = msg

  case class AccessCold(field: Symbol, trace: Seq[Tree]) extends Error:
    def show(using Context): String =
      "Access field on a value with an unknown initialization status."

  case class CallCold(meth: Symbol, trace: Seq[Tree]) extends Error:
    def show(using Context): String =
      "Call method on a value with an unknown initialization" + "."

  case class CallUnknown(meth: Symbol, trace: Seq[Tree]) extends Error:
    def show(using Context): String =
      val prefix = if meth.is(Flags.Method) then "Calling the external method " else "Accessing the external field"
      prefix + meth.show + " may cause initialization errors" + "."

  /** Promote a value under initialization to fully-initialized */
  case class UnsafePromotion(msg: String, trace: Seq[Tree], error: Error) extends Error:
    override def issue(using Context): Unit =
      report.warning(show, this.pos)

    def show(using Context): String =
      msg + stacktrace + "\n" +
        "Promoting the value to fully initialized failed due to the following problem:\n" +
        error.show + error.stacktrace
