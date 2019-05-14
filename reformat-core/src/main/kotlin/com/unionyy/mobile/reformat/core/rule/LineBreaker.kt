package com.unionyy.mobile.reformat.core.rule

import com.unionyy.mobile.reformat.core.FormatContext
import com.unionyy.mobile.reformat.core.FormatRule
import com.unionyy.mobile.reformat.core.Location
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.COMMA
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.DOT
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.END_OF_LINE_COMMENT
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.LPARENTH
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.PLUS
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.RPARENTH
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.STRING_LITERAL
import org.jetbrains.kotlin.com.intellij.psi.PsiDeclarationStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiIfStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiPolyadicExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.CompositeElement
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.FileElement
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaElementType.BINARY_EXPRESSION
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaElementType.FIELD
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaElementType.LITERAL_EXPRESSION
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaElementType.PARAMETER
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.PsiCoreCommentImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.java.ParameterListElement
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.java.PsiJavaTokenImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.java.PsiPolyadicExpressionImpl
import org.jetbrains.kotlin.psi.psiUtil.children

class LineBreaker : FormatRule {

    companion object {

        private const val maxLineLength = 120

        private const val lineBreak = "\n"

        private const val indent = "    "

        private fun lineBreak(
            context: FormatContext,
            lineStart: Int,
            moreIndent: String = ""
        ): ASTNode {
            val startNode = context.fileContent.psi.findElementAt(lineStart)?.node
                ?: return PsiWhiteSpaceImpl(lineBreak)
            return lineBreak(startNode, moreIndent)
        }

        private fun lineBreak(
            startNode: ASTNode,
            moreIndent: String = ""
        ): ASTNode {
            var lastIndent = ""
            if (startNode is PsiWhiteSpace) {
                lastIndent = getIndent((startNode as PsiWhiteSpace).text)
            } else if (startNode.treePrev is PsiWhiteSpace) {
                lastIndent = getIndent((startNode.treePrev as PsiWhiteSpace).text)
            } else if (startNode.treePrev == null && startNode.treeParent != null) {
                return lineBreak(startNode.treeParent, moreIndent)
            }
            return PsiWhiteSpaceImpl(lineBreak + lastIndent + moreIndent)
        }

        private fun getIndent(txt: String): String {
            val idx = txt.lastIndexOf(lineBreak)
            return if (idx in 0..txt.length - 2) {
                txt.substring(idx + 1)
            } else {
                txt
            }
        }
    }

    private data class Line(
        val lineNum: Int,
        val start: Int,
        val end: Int,
        val exceed: Boolean,
        val txt: String,
        val line: Line? = null
    )

    //map lineNum -> line(startOffset, endOffset)
    private val lines = mutableMapOf<Int, Line>()

    private val toBeLineBreak = mutableListOf<LineBreakAction>()

    private interface LineBreakAction {

        fun run(context: FormatContext)

        fun report(context: FormatContext)
    }

    override fun beforeVisit(context: FormatContext) {
        super.beforeVisit(context)
        lines.clear()
        toBeLineBreak.clear()
    }

    override fun visit(context: FormatContext, node: ASTNode) {
        if (context.language != JavaLanguage.INSTANCE) {
            return
        }
        if (node is FileElement) {
            visitWholeFile(node)
        } else {

            if (lines.isEmpty()) {
                return
            }

            val location = context.getCodeLocation(node)
            val line = lines.getValue(location.line)

            if (node is ParameterListElement) {
                breakFunctionParam(context, node, line)
            } else if (node.elementType == END_OF_LINE_COMMENT) {
                breakComment(context, node, line)
            } else if (node is PsiIfStatement) {
                breakIfStatement(context, node, line)
            } else if (node.elementType == JavaTokenType.QUEST ||
                node.elementType == JavaTokenType.COLON) {
                breakQuest(context, node, line)
            } else if (node.elementType == DOT) {
                breakDot(context, node, line, location)
            } else if (node.elementType == STRING_LITERAL) {
                breakStringLiteral(context, node, line)
            }
        }
    }

    private fun breakFunctionParam(
        context: FormatContext,
        node: ASTNode,
        line: Line
    ) {
        val paramNum = node.children().count {
            it.elementType == PARAMETER
        }
        if (paramNum > 4 || line.exceed) {
            node.getChildren(null).forEach { child ->
                if (child.elementType == COMMA ||
                    child.elementType == LPARENTH) {
                    val whiteSpaceExpect = child.treeNext
                    toBeLineBreak.add(
                        NormalLineBreak(
                            whiteSpaceExpect,
                            lineBreak(context, line.start, indent)
                        )
                    )
                } else if (child.elementType == RPARENTH) {
                    toBeLineBreak.add(
                        NormalLineBreak(
                            child,
                            lineBreak(context, line.start)
                        )
                    )
                }
            }
        }
    }

    private fun breakComment(
        context: FormatContext,
        node: ASTNode,
        line: Line
    ) {
        if (line.exceed) {
            val parent = node.treeParent
            if (parent != null && (
                    parent.elementType == FIELD ||
                        parent is PsiDeclarationStatement ||
                        parent is PsiExpressionStatement
                    )
            ) {
                toBeLineBreak.add(
                    MoveCommentToStart(
                        node,
                        parent,
                        lineBreak(context, parent.startOffset - 1))
                )
            } else {
                toBeLineBreak.add(
                    NormalLineBreak(
                        node,
                        lineBreak(context, line.start))
                )
            }

            toBeLineBreak.add(CutComment(node))
        }
    }

    private fun breakIfStatement(
        context: FormatContext,
        node: ASTNode,
        line: Line
    ) {
        fun ASTNode?.doBreak() {
            val target = this ?: return
            toBeLineBreak.add(
                NormalLineBreak(
                    target,
                    lineBreak(context, line.start, indent + indent))
            )
        }
        //if 语句判断 or else if
        if (line.exceed) {
            val ifState = node.findChildByType(BINARY_EXPRESSION) ?: return
            ifState.findChildByType(JavaTokenType.OROR).doBreak()
            ifState.findChildByType(JavaTokenType.ANDAND).doBreak()
            ifState.findChildByType(JavaTokenType.OR).doBreak()
            ifState.findChildByType(JavaTokenType.AND).doBreak()
        }
    }

    private fun breakQuest(context: FormatContext, node: ASTNode, line: Line) {
        //三目运算符里的逗号和冒号
        if (line.exceed) {
            toBeLineBreak.add(
                NormalLineBreak(
                    node,
                    lineBreak(
                        context,
                        line.start,
                        indent + indent)
                )
            )
        }
    }

    private fun breakDot(context: FormatContext, node: ASTNode, line: Line, location: Location) {
        //这里面得加上判断如果是if的话，且blabla就跳过，我想想怎么处理呢，一点都不好处理
        if (line.exceed && context.scanningTimes > 1) {
            val parent = node.treeParent
            if (parent != null && (parent is PsiReferenceExpression)) {
                val column = location.column
                val parentText = (parent as ASTNode).text
                val nextParentTextLength = parent.treeNext?.textLength ?: 0
                val totalLength = column + nextParentTextLength +
                    parentText.split(".")[1].length
                if (line.txt.indexOf(".") == (column - 1) && totalLength <= 119) {
                    return
                }
                toBeLineBreak.add(
                    NormalLineBreak(
                        node,
                        lineBreak(
                            context,
                            line.start,
                            indent + indent
                        )
                    )
                )
            }
        }
    }

    private fun breakStringLiteral(
        context: FormatContext,
        node: ASTNode, //STRING_LITERAL
        line: Line
    ) {
        val literal = node.treeParent
        if (literal != null && literal.elementType == LITERAL_EXPRESSION) {
            if (node.textLength > CutString.MAX_STRING_LEN) {
                toBeLineBreak.add(
                    CutString(literal, lineBreak(context, line.start, indent)))
            }
        }
    }

    /**
     * 在 [toBeBreak] 的位置插入一个换行的 [lineBreak]
     */
    private class NormalLineBreak(
        val toBeBreak: ASTNode,
        val lineBreak: ASTNode
    ) : LineBreakAction {

        override fun run(context: FormatContext) {
            when {
                toBeBreak is PsiWhiteSpace -> {
                    toBeBreak.treeParent.replaceChild(toBeBreak, lineBreak)
                }
                toBeBreak.treePrev is PsiWhiteSpace -> {
                    toBeBreak.treeParent.replaceChild(toBeBreak.treePrev, lineBreak)
                }
                else -> {
                    toBeBreak.treeParent.addChild(lineBreak, toBeBreak)
                }
            }
        }

        override fun report(context: FormatContext) {
            context.report(
                "Add a line break.",
                context.getCodeFragment(toBeBreak))
        }
    }

    /**
     * 把注释 [comment] 挪到 [parent] 的开头
     */
    private class MoveCommentToStart(
        val comment: ASTNode,
        val parent: ASTNode,
        val lineBreak: ASTNode
    ) : LineBreakAction {

        override fun run(context: FormatContext) {
            val whiteSpace = comment.treePrev
            if (whiteSpace is PsiWhiteSpace) {
                parent.removeChild(whiteSpace)
            }
            parent.removeChild(comment)
            val anchor = parent.children().firstOrNull()
            parent.addChild(comment, anchor)
            parent.addChild(lineBreak, anchor)
        }

        override fun report(context: FormatContext) {
            context.report(
                "Move comment to the start.",
                context.getCodeFragment(comment))
        }
    }

    /**
     * 如果注释 [comment] 过长，需要裁剪
     */
    private class CutComment(
        val comment: ASTNode
    ) : LineBreakAction {

        override fun run(context: FormatContext) {
            context.notifyTextChange()

            var startNode = comment
            var totalLength = comment.textLength
            val whiteSpace = comment.treePrev
            if (whiteSpace is PsiWhiteSpace) {
                startNode = whiteSpace
                totalLength += getIndent((whiteSpace as PsiWhiteSpace).text).length
            }

            if (totalLength >= maxLineLength) {
                val lineBreakText = lineBreak(startNode, "").text
                val whiteSpaceLen = lineBreakText.length

                fun checkAndCutComment(comment: String, addToTree: (ASTNode) -> Unit) {
                    if (comment.length + whiteSpaceLen >= maxLineLength) {
                        val half = comment.length / 2
                        checkAndCutComment(comment.substring(0, half), addToTree)
                        addToTree(PsiWhiteSpaceImpl(lineBreakText))
                        checkAndCutComment(comment.substring(half), addToTree)
                    } else {
                        val text =
                            if (comment.startsWith("//")) {
                                comment
                            } else {
                                "//$comment"
                            }
                        addToTree(PsiCoreCommentImpl(END_OF_LINE_COMMENT, text))
                    }
                }

                checkAndCutComment(comment.text) { child ->
                    comment.treeParent.addChild(child, comment)
                }
                comment.treeParent.removeChild(comment)
            }
        }

        override fun report(context: FormatContext) {
            context.report(
                "Cut the too long comment: ${comment.text}.",
                context.getCodeFragment(comment))
        }
    }

    private class CutString(
        val string: ASTNode, //LITERAL_EXPRESSION
        val lineBreakNode: ASTNode
    ) : LineBreakAction {

        companion object {
            const val MAX_STRING_LEN = 80
        }

        override fun run(context: FormatContext) {
            var polyadicExp = string.treeParent
            val anchorBefore: ASTNode?
            if (polyadicExp is PsiPolyadicExpression) {
                anchorBefore = string
            } else {
                polyadicExp = PsiPolyadicExpressionImpl()
                anchorBefore = null
                string.treeParent.replaceChild(string, polyadicExp)
            }

            cutStringIfNeed(string.text, lineBreakNode.text, true) { child ->
                polyadicExp.addChild(child, anchorBefore)
            }

            if (anchorBefore === string) {
                string.treeParent.removeChild(string)
            }
        }

        override fun report(context: FormatContext) {
            context.report(
                "Cut the too long String literal: ${string.text}.",
                context.getCodeFragment(string))
        }

        private fun cutStringIfNeed(
            node: String,
            lineBreak: String,
            mustCut: Boolean,
            addToTree: (ASTNode) -> Unit
        ) {
            if (mustCut || node.length > 80) {
                val half = node.length / 2
                cutStringIfNeed(
                    node.substring(0, half),
                    lineBreak,
                    false,
                    addToTree)
                addToTree(PsiWhiteSpaceImpl(" "))
                addToTree(PsiJavaTokenImpl(PLUS, "+"))
                addToTree(PsiWhiteSpaceImpl(lineBreak))
                cutStringIfNeed(
                    node.substring(half),
                    lineBreak,
                    false,
                    addToTree)
            } else {
                val literal = CompositeElement(LITERAL_EXPRESSION)
                addToTree(literal)
                var txtWithQuot: String = node
                if (!node.startsWith("\"")) {
                    txtWithQuot = "\"" + node
                }
                if (!node.endsWith("\"")) {
                    txtWithQuot = node + "\""
                }
                literal.addChild(PsiJavaTokenImpl(STRING_LITERAL, txtWithQuot))
            }
        }
    }

    override fun afterVisit(context: FormatContext) {
        super.afterVisit(context)
        toBeLineBreak.forEach { it.report(context) }
        toBeLineBreak.forEach { it.run(context) }

        if (context.reportCnt > 0 && context.scanningTimes < 2) {
            context.requestRepeatScan()
        }
    }

    private fun visitWholeFile(file: FileElement) {
        var lineStart = 0
        var lineEnd = 0
        val textLines = file.text.split("\n")
        for ((index, line) in textLines.withIndex()) {
            val lineNum = index + 1
            lineEnd += line.length + 1
            lines[lineNum] = Line(
                lineNum, lineStart, lineEnd, line.length > maxLineLength, line)
            lineStart = lineEnd + 1
        }
    }
}