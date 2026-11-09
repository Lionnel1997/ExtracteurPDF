package ga.extracteur

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val seuil = 50
    private val partMin = 0.5
    private val codePdf = 1

    private lateinit var statut: TextView
    private lateinit var btnPartager: Button
    private lateinit var modes: RadioGroup
    private var zip: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        modes = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("Auto", "OCR", "Natif").forEachIndexed { i, t ->
                addView(RadioButton(context).apply { id = 100 + i; text = t })
            }
            check(100)
        }
        val btnChoisir = Button(this).apply {
            text = "Choisir un ou plusieurs PDF"
            setOnClickListener { choisir() }
        }
        btnPartager = Button(this).apply {
            text = "Partager le résultat (ZIP)"
            isEnabled = false
            setOnClickListener { partager() }
        }
        statut = TextView(this).apply {
            text = "Aucun fichier traité."
            textSize = 15f
        }
        colonne.addView(modes)
        colonne.addView(btnChoisir)
        colonne.addView(btnPartager)
        colonne.addView(ScrollView(this).apply { addView(statut) })
        setContentView(colonne)

        if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (u != null) traiter(listOf(u))
        }
    }

    private fun choisir() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, codePdf)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != codePdf || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c ->
            for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri)
        }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        if (uris.isNotEmpty()) traiter(uris)
    }

    private fun maj(s: String) = runOnUiThread { statut.text = s }

    private fun traiter(uris: List<Uri>) {
        btnPartager.isEnabled = false
        val mode = when (modes.checkedRadioButtonId) {
            101 -> "ocr"
            102 -> "natif"
            else -> "auto"
        }
        thread {
            val rapport = StringBuilder()
            try {
                val sortie = File(cacheDir, "sortie").apply { deleteRecursively(); mkdirs() }
                val reco = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try {
                    uris.forEachIndexed { k, uri ->
                        val nom = nomFichier(uri, k)
                        maj("Fichier ${k + 1}/${uris.size} : $nom")
                        try {
                            rapport.append(traiterPdf(uri, nom, sortie, mode, reco))
                        } catch (e: Throwable) {
                            rapport.append("$nom : échec (${e.message})\n\n")
                        }
                    }
                } finally {
                    reco.close()
                }
                val dossierZip = File(cacheDir, "partage").apply { mkdirs() }
                val z = File(dossierZip, "extraction.zip")
                if (z.exists()) z.delete()
                zipper(sortie, z)
                zip = z
                runOnUiThread { btnPartager.isEnabled = true }
                maj("Terminé.\n\n$rapport")
            } catch (e: Throwable) {
                maj("Erreur : ${e.message}")
            }
        }
    }

    private fun nomFichier(uri: Uri, k: Int): String {
        var nom: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && c.moveToFirst()) nom = c.getString(col)
        }
        val base = (nom ?: "document").removeSuffix(".pdf").removeSuffix(".PDF")
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        return "%02d_%s".format(k + 1, base)
    }

    private fun nbAlnum(t: String) = t.count { it.isLetterOrDigit() }

    private fun valide(texte: String): Boolean {
        val t = texte.trim()
        if (t.split("(cid:").size - 1 >= 6) return false
        val alnum = nbAlnum(t)
        if (alnum < seuil) return false
        val nonEsp = t.count { !it.isWhitespace() }
        return nonEsp > 0 && alnum.toDouble() / nonEsp >= 0.6
    }

    private fun ocrPage(rendu: PdfRenderer, index: Int, reco: TextRecognizer): String {
        val page = rendu.openPage(index)
        try {
            val echelle = minOf(300f / 72f, 3000f / maxOf(page.width, page.height))
            val bmp = Bitmap.createBitmap(
                (page.width * echelle).toInt(),
                (page.height * echelle).toInt(),
                Bitmap.Config.ARGB_8888
            )
            bmp.eraseColor(0xFFFFFFFF.toInt())
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val t = Tasks.await(reco.process(InputImage.fromBitmap(bmp, 0))).text
            bmp.recycle()
            return t
        } finally {
            page.close()
        }
    }

    private fun images(res: PDResources?, page: Int, dossier: File, compteur: IntArray) {
        if (res == null) return
        for (nom in res.xObjectNames) {
            try {
                val x = res.getXObject(nom)
                if (x is PDImageXObject) {
                    val bmp = x.image
                    if (bmp.width >= 50 && bmp.height >= 50) {
                        compteur[0]++
                        val png = bmp.hasAlpha()
                        val f = File(dossier, "p%03d_img%03d.%s".format(page, compteur[0], if (png) "png" else "jpg"))
                        f.outputStream().use {
                            bmp.compress(
                                if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                                90, it
                            )
                        }
                    }
                } else if (x is PDFormXObject) {
                    images(x.resources, page, dossier, compteur)
                }
            } catch (e: Throwable) {
                // image illisible : on passe à la suivante
            }
        }
    }

    private fun traiterPdf(uri: Uri, nom: String, sortie: File, mode: String, reco: TextRecognizer): String {
        val pdf = File(cacheDir, "entree.pdf")
        contentResolver.openInputStream(uri)!!.use { i -> pdf.outputStream().use { o -> i.copyTo(o) } }

        val dossierImg = File(sortie, "${nom}_images").apply { mkdirs() }
        val compteur = IntArray(1)
        val resultats = ArrayList<Triple<Int, String, String>>()
        var decision = ""
        var rendu: PdfRenderer? = null
        val doc = PDDocument.load(pdf)

        return try {
            val nb = doc.numberOfPages
            val stripper = PDFTextStripper().apply { sortByPosition = true }

            // 1) extraction native de toutes les pages
            val natifs = (1..nb).map { p ->
                try {
                    stripper.startPage = p
                    stripper.endPage = p
                    stripper.getText(doc)
                } catch (e: Exception) {
                    ""
                }
            }
            val valides = natifs.map { valide(it) }
            val part = if (nb > 0) valides.count { it }.toDouble() / nb else 0.0

            // 2) décision par document
            val toutOcr: Boolean
            when {
                mode == "ocr" -> { decision = "OCR (forcé)"; toutOcr = true }
                mode == "natif" -> { decision = "natif (forcé)"; toutOcr = false }
                part < partMin -> {
                    decision = "OCR (seulement ${(part * 100).toInt()} % de pages natives valides)"
                    toutOcr = true
                }
                else -> {
                    decision = "natif (${(part * 100).toInt()} % de pages valides, OCR en secours)"
                    toutOcr = false
                }
            }

            // 3) construction page par page, avec repli
            for (i in 0 until nb) {
                val num = i + 1
                val natif = natifs[i]
                maj("$nom : page $num/$nb")

                if (mode == "natif" || (!toutOcr && valides[i])) {
                    resultats.add(Triple(num, "natif", natif))
                } else {
                    var ocr = ""
                    var erreur: String? = null
                    try {
                        val r = rendu ?: PdfRenderer(
                            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
                        ).also { rendu = it }
                        ocr = ocrPage(r, i, reco)
                    } catch (e: Throwable) {
                        erreur = e.message ?: "erreur"
                    }
                    resultats.add(
                        when {
                            erreur == null && valide(ocr) -> Triple(num, "ocr", ocr)
                            nbAlnum(ocr) < nbAlnum(natif) -> Triple(num, "repli-natif", natif)
                            ocr.isNotBlank() -> Triple(num, "ocr", ocr)
                            erreur != null -> Triple(num, "erreur", "[Échec OCR : $erreur]")
                            else -> Triple(num, "vide", "")
                        }
                    )
                }

                try {
                    images(doc.getPage(i).resources, num, dossierImg, compteur)
                } catch (e: Throwable) {
                }
            }

            File(sortie, "$nom.txt").bufferedWriter(Charsets.UTF_8).use { w ->
                for ((num, methode, texte) in resultats) {
                    w.write("\n--- Page $num (methode: $methode) ---\n")
                    w.write(texte)
                }
            }

            fun n(m: String) = resultats.count { it.second == m }
            val repli = resultats.filter { it.second == "repli-natif" }.map { it.first }
            var r = "$nom\n  Décision : $decision\n" +
                "  $nb page(s) : ${n("natif")} natif, ${n("ocr")} OCR, ${n("repli-natif")} repli natif, " +
                "${n("vide")} vide(s), ${n("erreur")} erreur(s)\n" +
                "  Images extraites : ${compteur[0]}\n"
            if (repli.isNotEmpty()) r += "  OCR rejeté, natif récupéré : pages $repli\n"
            r + "\n"
        } finally {
            rendu?.close()
            doc.close()
        }
    }

    private fun zipper(source: File, cible: File) {
        ZipOutputStream(cible.outputStream().buffered()).use { z ->
            source.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(source).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    private fun partager() {
        val f = zip ?: return
        val u = FileProvider.getUriForFile(this, "$packageName.partage", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Partager l'extraction"))
    }
}
