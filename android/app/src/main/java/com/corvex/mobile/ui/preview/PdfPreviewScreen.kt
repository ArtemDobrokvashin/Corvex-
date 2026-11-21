package com.corvex.mobile.ui.preview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

@Composable
fun PdfPreviewScreen(pdfFile: File) {
    val renderer = remember(pdfFile) {
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd)
    }

    DisposableEffect(renderer) {
        onDispose { renderer.close() }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(renderer.pageCount) { pageIndex ->
            val bitmap = remember(pageIndex) {
                renderer.openPage(pageIndex).use { page ->
                    val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}"
            )
        }
    }
}
