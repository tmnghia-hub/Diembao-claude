package com.nononsenseapps.feeder.openai

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

private val BLOCK_TAGS =
    setOf(
        "p", "div", "section", "article", "blockquote", "ul", "ol", "table", "tr",
        "figure", "figcaption", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "li",
    )

/**
 * Turns article HTML into plain text, keeping every paragraph, heading, list item and caption.
 */
fun htmlToPlainText(html: String): String {
    val doc = Jsoup.parse(html)
    doc.select("script,style,noscript").remove()
    val sb = StringBuilder()
    NodeTraversor.traverse(
        object : NodeVisitor {
            override fun head(
                node: Node,
                depth: Int,
            ) {
                when (node) {
                    is TextNode -> {
                        val text = node.text()
                        if (text.isBlank()) {
                            if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
                        } else {
                            sb.append(text)
                        }
                    }

                    is Element ->
                        when {
                            node.tagName() == "br" -> sb.append('\n')
                            node.tagName() == "li" -> sb.append("\n- ")
                            node.tagName() in BLOCK_TAGS && sb.isNotEmpty() && !sb.endsWith("\n") -> sb.append('\n')
                        }
                }
            }

            override fun tail(
                node: Node,
                depth: Int,
            ) {
                if (node is Element && node.tagName() in BLOCK_TAGS && node.tagName() != "li") {
                    sb.append("\n\n")
                }
            }
        },
        doc.body(),
    )
    return sb
        .toString()
        .lines()
        .joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/**
 * The message sent to an AI assistant app: a prompt that asks for the whole article to be translated
 * without cutting or summarising, followed by the article text.
 */
fun buildAiAssistantMessage(
    title: String,
    link: String?,
    plainText: String,
    targetLanguage: String,
    options: AiTranslationOptions,
): String {
    val target = targetLanguage.trim().ifBlank { "Vietnamese" }
    val rules =
        listOfNotNull(
            "Translate the WHOLE article below into $target, from the title to the very last line. " +
                "Do not cut, shorten, summarise, merge or skip any paragraph, sentence, quotation, number, list item or caption. " +
                "If the answer becomes too long for one message, stop at the end of a paragraph and wait for me to say \"continue\". " +
                "Never compress the text to make it fit.",
            "Stay very close to the content of the article. Do not add your own opinions, background information or comments, " +
                "do not explain the story, and keep the paragraphs in the same order.",
            "Only make the wording easier to understand: prefer common words and clear, natural sentences instead of difficult or " +
                "literary ones. Keep the meaning, facts, names, numbers, dates, units and quotations exactly as in the article.",
            "Writing style: ${options.styleInstruction}",
            if (options.annotateTerms) glossaryInstruction(target) else null,
            "Output only the translation, starting with the translated title. No introduction and no closing remarks.",
        )
    return buildString {
        appendLine("Please do the following task.")
        appendLine()
        rules.forEachIndexed { index, rule -> appendLine("${index + 1}. $rule") }
        appendLine()
        appendLine("=== ARTICLE START ===")
        appendLine("Title: $title")
        if (!link.isNullOrBlank()) appendLine("Source: $link")
        appendLine()
        appendLine(plainText)
        appendLine("=== ARTICLE END ===")
    }
}
