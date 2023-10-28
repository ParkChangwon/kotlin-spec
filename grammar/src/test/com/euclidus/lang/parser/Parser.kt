package com.euclidus.lang.parser

import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.atn.PredictionContext
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNodeImpl
import org.jetbrains.kotlin.spec.grammar.FXMathLexer
import org.jetbrains.kotlin.spec.grammar.FXMathParser
import org.jetbrains.kotlin.spec.grammar.parsetree.ParseTreeUtil
import java.io.File
import java.util.HashMap
import java.util.regex.Pattern

enum class FXMathParseTreeNodeType { RULE, TERMINAL }

val ls: String = System.lineSeparator()

class FXMathParseTree(
    private val type: FXMathParseTreeNodeType,
    private val name: String,
    private val text: String? = null,
    val children: MutableList<FXMathParseTree> = mutableListOf()
) {
    private fun stringifyTree(builder: StringBuilder, node: FXMathParseTree, depth: Int = 1): StringBuilder =
        builder.apply {
            node.children.forEach { child ->
                when (child.type) {
                    FXMathParseTreeNodeType.RULE -> append("  ".repeat(depth) + child.name + ls)
                    FXMathParseTreeNodeType.TERMINAL -> append(
                        "  ".repeat(depth) + child.name +
                                "(\"" + child.text!!.replace(ls, Pattern.quote(ls)) + "\")" + ls
                    )
                }
                stringifyTree(builder, child, depth + 1)
            }
        }

    fun stringifyTree(root: String) = root + ls + stringifyTree(StringBuilder(), this)
}

data class SyntaxError(val message: String?, val line: Int, val charPosition: Int) {
    override fun toString() =  "Line $line:$charPosition $message"
}

class FXMathLexerWithLimitedCache(input: CharStream): FXMathLexer(input) {
    companion object {
        private const val MAX_CACHE_SIZE = 10000
        private val cacheField = _sharedContextCache.javaClass.declaredFields.find { it.name == "cache" }!!.apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private val cache = cacheField.get(_sharedContextCache) as HashMap<PredictionContext, PredictionContext>
    }

    init {
        if (_sharedContextCache.size() > MAX_CACHE_SIZE) {
            cache.clear()
            reset()
            interpreter.clearDFA()
        }
    }
}

class KotlinParserWithLimitedCache(input: TokenStream): FXMathParser(input) {
    companion object {
        private const val MAX_CACHE_SIZE = 10000
        private val cacheField = _sharedContextCache.javaClass.declaredFields.find { it.name == "cache" }!!.apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private val cache = cacheField.get(_sharedContextCache) as HashMap<PredictionContext, PredictionContext>
    }

    init {
        if (_sharedContextCache.size() > MAX_CACHE_SIZE) {
            cache.clear()
            reset()
            interpreter.clearDFA()
        }
    }
}

class Parser {
    private fun buildTree(
        parser: FXMathParser,
        tokenTypeMap: Map<String, Int>,
        antlrParseTree: ParseTree,
        fxMathParseTree: FXMathParseTree
    ): FXMathParseTree {
        for (i in 0..antlrParseTree.childCount) {
            val antlrParseTreeNode = antlrParseTree.getChild(i) ?: continue
            val kotlinParseTreeNode = when (antlrParseTreeNode) {
                is TerminalNodeImpl ->
                    FXMathParseTree(
                        FXMathParseTreeNodeType.TERMINAL,
                        FXMathLexer.VOCABULARY.getSymbolicName(antlrParseTreeNode.symbol.type),
                        antlrParseTreeNode.symbol.text.replace(ls, "\\n")
                    )
                else ->
                    FXMathParseTree(
                        FXMathParseTreeNodeType.RULE,
                        parser.ruleNames[(antlrParseTreeNode as RuleContext).ruleIndex]
                    )
            }

            fxMathParseTree.children.add(kotlinParseTreeNode)

            buildTree(parser, tokenTypeMap, antlrParseTreeNode, kotlinParseTreeNode)
        }

        return fxMathParseTree
    }

    private fun getErrorListener(errors: MutableList<SyntaxError>) =
        object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String?,
                e: RecognitionException?
            ) {
                errors.add(SyntaxError(msg, line, charPositionInLine))
            }
        }


    fun parse(sourceCode: String): Pair<FXMathParseTree, Pair<List<SyntaxError>, List<SyntaxError>>> {
        val lexerErrors = mutableListOf<SyntaxError>()
        val parserErrors = mutableListOf<SyntaxError>()
        val lexer = FXMathLexerWithLimitedCache(CharStreams.fromString(sourceCode))
        val tokens = CommonTokenStream(
            lexer.apply {
                removeErrorListeners()
                addErrorListener(getErrorListener(lexerErrors))
            }
        )
        val kotlinParser = KotlinParserWithLimitedCache(tokens)
        val parseTree = kotlinParser.apply {
            removeErrorListeners()
            addErrorListener(getErrorListener(parserErrors))
        }.fxMathFile() as ParseTree
        val FXMathParseTree = buildTree(
            kotlinParser,
            lexer.tokenTypeMap,
            parseTree,
            FXMathParseTree(
                FXMathParseTreeNodeType.RULE,
                kotlinParser.ruleNames[kotlinParser.ruleIndexMap["fxMathFile"]!!]
            )
        )

        return Pair(FXMathParseTree, Pair(lexerErrors, parserErrors))
    }

}

fun main() {

    val testFile = File("./testData/fxmath/test.txt")
    val testText = testFile.readText()
    val parser = Parser()
    val (parseTree, errors) = parser.parse(testText)
    val (lexerErrors, parserErrors) = errors

    lexerErrors.forEach { println("    - $it") }
    parserErrors.forEach { println("    - $it") }
}