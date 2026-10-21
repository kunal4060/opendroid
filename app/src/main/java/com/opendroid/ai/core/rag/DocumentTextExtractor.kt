package com.opendroid.ai.core.rag

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.util.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A user-readable extraction failure; the import surfaces it verbatim. */
class RagExtractionException(message: String) : Exception(message)

/**
 * Turns an imported file into [TextSegment]s for the chunker (feature D4).
 *
 * PDFs are extracted page by page with PdfBox (the same library and
 * initialisation pattern as B1's [com.opendroid.ai.core.llm.AttachmentProcessor])
 * so every chunk can cite its exact page. Plain text and Markdown are read
 * as UTF-8. Anything else — and any failure — raises
 * [RagExtractionException]: imports fail visibly, never silently "succeed"
 * with no content (checklist requirement).
 */
@Singleton
class DocumentTextExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Extracts [file] (a staged local copy of the picked document).
     * [mimeType] is the resolver-reported type; [displayName] is only used
     * to recognise Markdown/text by extension when the resolver reported a
     * generic type.
     */
    suspend fun extract(
        file: File,
        mimeType: String?,
        displayName: String
    ): List<TextSegment> = withContext(Dispatchers.IO) {
        when {
            isPdf(mimeType, displayName) -> extractPdf(file)
            isText(mimeType, displayName) -> extractText(file)
            else -> throw RagExtractionException(
                "That file type isn't supported. Import a PDF or a text/Markdown file."
            )
        }
    }

    private fun extractText(file: File): List<TextSegment> {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw RagExtractionException(
                "Couldn't read that file as text: ${e.localizedMessage ?: "read failed"}"
            )
        }
        if (text.isBlank()) {
            throw RagExtractionException("That file contains no text.")
        }
        return listOf(TextSegment(text = text, pageRef = null))
    }

    private fun extractPdf(file: File): List<TextSegment> {
        ensurePdfBoxInitialised()
        return try {
            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) {
                    throw RagExtractionException(
                        "That PDF is password-protected, so its text can't be read."
                    )
                }
                if (doc.numberOfPages > MAX_PDF_PAGES) {
                    throw RagExtractionException(
                        "That PDF has ${doc.numberOfPages} pages — over the $MAX_PDF_PAGES-page import limit."
                    )
                }
                val stripper = PDFTextStripper()
                val segments = ArrayList<TextSegment>()
                for (page in 1..doc.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val pageText = stripper.getText(doc).trim()
                    if (pageText.isNotEmpty()) {
                        segments += TextSegment(text = pageText, pageRef = page)
                    }
                }
                if (segments.isEmpty()) {
                    throw RagExtractionException(
                        "No readable text was found in that PDF. If it's a scan of " +
                            "images, it needs OCR, which isn't supported yet."
                    )
                }
                segments
            }
        } catch (e: RagExtractionException) {
            throw e
        } catch (e: Exception) {
            throw RagExtractionException(
                "Couldn't read that PDF: ${e.localizedMessage ?: "it may be corrupt"}"
            )
        }
    }

    private fun ensurePdfBoxInitialised() {
        synchronized(pdfBoxLock) {
            if (!pdfBoxInitialised) {
                PDFBoxResourceLoader.init(context)
                pdfBoxInitialised = true
            }
        }
    }

    companion object {
        const val MAX_PDF_PAGES = 1000

        fun isPdf(mimeType: String?, displayName: String): Boolean =
            mimeType == "application/pdf" ||
                (mimeType == "application/octet-stream" &&
                    displayName.endsWith(".pdf", ignoreCase = true))

        fun isText(mimeType: String?, displayName: String): Boolean {
            if (mimeType != null && mimeType.startsWith("text/")) return true
            val lower = displayName.lowercase()
            return lower.endsWith(".txt") || lower.endsWith(".md") ||
                lower.endsWith(".markdown")
        }

        private val pdfBoxLock = Any()
        @Volatile private var pdfBoxInitialised = false
    }
}

/** The MIME types the Knowledge Base import picker offers. */
val RAG_IMPORT_MIME_TYPES = arrayOf("application/pdf", "text/*")
