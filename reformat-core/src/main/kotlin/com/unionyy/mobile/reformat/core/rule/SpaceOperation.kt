package com.unionyy.mobile.reformat.core.rule

import com.unionyy.mobile.reformat.core.FormatContext
import com.unionyy.mobile.reformat.core.FormatRule
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.COMMA
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.DOT
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.LBRACE
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.LBRACKET
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.LPARENTH
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.RBRACE
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.RPARENTH
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType.SEMICOLON
import org.jetbrains.kotlin.com.intellij.psi.PsiEmptyStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiForStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaElementType.LITERAL_EXPRESSION
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaElementType.NAME_VALUE_PAIR
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaElementType.THROWS_LIST
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType

/**
 * Created BY PYF 2019/5/15
 * email: pengyangfan@yy.com
 *
 * 在一些关键词前后添加或移除空格
 */
class SpaceOperation : FormatRule {

    private val toBeAddSpace = mutableListOf<AddSpaceAction>()

    private val expelChar = mapOf(
        LBRACE to listOf(LBRACE, RBRACE, LPARENTH, LITERAL_EXPRESSION), // {{ {} {(
        RBRACE to listOf(SEMICOLON, RPARENTH, COMMA), // }; }) },
        RPARENTH to listOf(SEMICOLON, RBRACE, COMMA, LPARENTH, RPARENTH,
            LBRACKET, DOT, THROWS_LIST), // ); )} ), )) )[ )] ).
        THROWS_LIST to listOf(SEMICOLON, LBRACE), //; {
        SEMICOLON to listOf(),
        COMMA to listOf()
    )

    override fun beforeVisit(context: FormatContext) {
        super.beforeVisit(context)
        toBeAddSpace.clear()
    }

    override fun visit(context: FormatContext, node: ASTNode) {
        val allowNext = expelChar[node.elementType]
        if (allowNext != null) {
            spaceOperation(allowNext, node)
        }
        if (node.elementType == SEMICOLON) {
            subSpaceBeforeSemiColon(node)
        }
    }

    override fun afterVisit(context: FormatContext) {
        super.afterVisit(context)
        toBeAddSpace.forEach {
            it.report(context)
        }
        toBeAddSpace.forEach {
            try {
                it.spaceOperation()
            } catch (e: Exception) {
                context.notifyTextChange()
                it.report(context)
                throw e
            }
        }
    }

    private interface AddSpaceAction {
        fun spaceOperation()

        fun report(context: FormatContext)
    }

    private class AddSpaceSomewhere(
        val parent: ASTNode,
        val beforeNode: ASTNode
    ) : AddSpaceAction {
        override fun spaceOperation() {
            parent.addChild(PsiWhiteSpaceImpl(" "), beforeNode)
        }

        override fun report(context: FormatContext) {
            context.report("Add a space before ${beforeNode.text}",
                context.getCodeFragment(beforeNode))
        }
    }

    private class SubSpaceBeforeSemi(
        val whiteSpace: ASTNode
    ) : AddSpaceAction {
        override fun spaceOperation() {
            whiteSpace.treeParent.removeChild(whiteSpace)
        }

        override fun report(context: FormatContext) {
            context.report("remove a space at ${context.getCodeLocation(whiteSpace)}",
                context.getCodeFragment(whiteSpace))
        }
    }

    private fun spaceOperation(
        allowNext: List<IElementType>,
        node: ASTNode
    ) {
        val parent = node.getTheNonSpaceParent(allowNext)
        if (parent != null) {
            toBeAddSpace.add(AddSpaceSomewhere(parent.treeParent, parent.treeNext))
        }
    }

    private fun ASTNode.getTheNonSpaceParent(allowNext: List<IElementType>): ASTNode? {
        var now: ASTNode? = this
        while (now != null && now.treeNext == null) {
            now = now.treeParent
        }

        if (now != null) {
            val isAllow = now.treeNext is PsiWhiteSpace ||
                now.treeNext is PsiReferenceExpression ||
                allowNext.any { now.treeNext.elementType == it }
            if (!isAllow) {
                return now
            }
        }
        return null
    }

    private fun subSpaceBeforeSemiColon(
        node: ASTNode
    ) {
        val prev = node.treePrev
        val parent = node.treeParent
        // for(; ; )
//        if (parent is PsiForStatement && prev.treePrev is PsiEmptyStatement) {
//            return
//        }
        if (prev != null && prev is PsiWhiteSpace) {
            // ; 前有空格
            toBeAddSpace.add(SubSpaceBeforeSemi(prev))
        }
    }
}