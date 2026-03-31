package info.plateaukao.einkbro.unit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.database.Bookmark
import info.plateaukao.einkbro.database.BookmarkManager
import info.plateaukao.einkbro.database.RecordDb
import info.plateaukao.einkbro.preference.ConfigManager
import info.plateaukao.einkbro.view.EBToast
import info.plateaukao.einkbro.view.dialog.DialogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream


@OptIn(DelicateCoroutinesApi::class)
class BackupUnit(
    private val context: Context,
) : KoinComponent {
    private val bookmarkManager: BookmarkManager by inject()
    private val recordDb: RecordDb by inject()
    private val config: ConfigManager by inject()

    fun backupData(context: Context, uri: Uri): Boolean {
        try {
            val fos = context.contentResolver.openOutputStream(uri) ?: return false
            ZipOutputStream(fos).use { zos ->
                // Add databases to the zip file
                File(DATABASE_PATH).listFiles()?.forEach { dbFile ->
                    addFileToZip(zos, dbFile, "databases/${dbFile.name}")
                }

                // Add shared preferences to the zip file
                File(SHARED_PREFS_PATH).listFiles()?.forEach { sharedPrefsFile ->
                    addFileToZip(zos, sharedPrefsFile, "shared_prefs/${sharedPrefsFile.name}")
                }

                // Add user extensions to the zip file
                File(context.filesDir, USER_EXTENSIONS_DIR).listFiles()?.forEach { extensionFile ->
                    addFileToZip(zos, extensionFile, "$USER_EXTENSIONS_DIR/${extensionFile.name}")
                }

                val extensionIndexFile = File(context.filesDir, EXTENSIONS_INDEX_FILE)
                if (extensionIndexFile.exists()) {
                    addFileToZip(zos, extensionIndexFile, EXTENSIONS_INDEX_FILE)
                }
            }
            EBToast.show(context, R.string.toast_backup_successful)
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
    }

    fun restoreBackupData(context: Context, uri: Uri): Boolean {
        try {
            bookmarkManager.database.close()
            recordDb.close()

            val fis = context.contentResolver.openInputStream(uri) ?: return false
            val zis = ZipInputStream(fis)

            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                val file = resolveRestoreTarget(context, zipEntry.name)
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { fos ->
                    val buffer = ByteArray(1024)
                    var length = zis.read(buffer)
                    while (length > 0) {
                        fos.write(buffer, 0, length)
                        length = zis.read(buffer)
                    }
                }
                zipEntry = zis.nextEntry
            }
            zis.close()
            fis.close()
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
    }

    fun importBookmarks(uri: Uri) {
        GlobalScope.launch {
            try {
                val contentString = getFileContentString(uri)
                // detect if the content is a json array
                val bookmarks = if (contentString.startsWith("[")) {
                    JSONArray(contentString).toJSONObjectList().map { json -> json.toBookmark() }
                } else {
                    //parseHtmlToBookmarkList(contentString)
                    parseChromeBookmarks(contentString)
                }

                if (bookmarks.isNotEmpty()) {
                    bookmarkManager.overwriteBookmarks(bookmarks)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Bookmarks are imported", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Bookmarks import failed", Toast.LENGTH_SHORT)
                        .show()
                }
                if (e is SecurityException) {
                    config.bookmarkSyncUrl = ""
                }
            }
        }
    }

    private suspend fun getFileContentString(uri: Uri): String {
        return withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri).use {
                it?.bufferedReader()?.readText().orEmpty()
            }
        }
    }

    private var recordId = 0
    private fun parseChromeBookmarks(html: String): List<Bookmark> {
        val doc = Jsoup.parse(html)
        val bookmarks = dlElement(doc.select("DL").first()!!.children(), recordId)
        recordId = 0
        return bookmarks
    }

    private fun dlElement(elements: Elements, parentId: Int): List<Bookmark> {
        val bookmarkList = mutableListOf<Bookmark>()
        for (elem in elements) {
            when (elem.nodeName().uppercase()) {
                "DT" -> bookmarkList.addAll(dtElement(elem.children(), parentId))
                "DL" -> bookmarkList.addAll(dlElement(elem.children(), parentId))
                "P" -> continue
                else -> {}
            }
        }
        return bookmarkList
    }

    private var currentFolderId = 0
    private fun dtElement(elements: Elements, parentId: Int): List<Bookmark> {
        val bookmarkList = mutableListOf<Bookmark>()
        for (elem in elements) {
            when (elem.nodeName().uppercase()) {
                "H3" -> {
                    currentFolderId = ++recordId
                    bookmarkList.add(
                        Bookmark(
                            elem.text(),
                            "",
                            true,
                            parentId,
                        ).apply { id = currentFolderId }
                    )
                }

                "A" -> bookmarkList.add(
                    Bookmark(
                        elem.text(),
                        elem.attr("href"),
                        false,
                        parentId,
                    ).apply { id = ++recordId }
                )

                "DL" -> bookmarkList.addAll(dlElement(elem.children(), currentFolderId))
                "P" -> continue
                else -> {}
            }
        }
        return bookmarkList
    }

    private fun elementToBookmarks(element: Element): List<Bookmark> {
        val bookmarkList = mutableListOf<Bookmark>()
        val bookmarkElements = element.select("a")
        for (bookmarkElement in bookmarkElements) {
            val bookmark = Bookmark(
                bookmarkElement.text(),
                bookmarkElement.attr("href"),
            )
            bookmarkList.add(bookmark)
        }
        return bookmarkList
    }

    private fun JSONArray.toJSONObjectList() =
        (0 until length()).map { get(it) as JSONObject }

    fun exportBookmarks(uri: Uri, showToast: Boolean = true) {
        GlobalScope.launch {
            val bookmarks = bookmarkManager.getAllBookmarks()
            try {
                context.contentResolver.openOutputStream(uri).use {
                    it?.write(bookmarks.toJsonString().toByteArray())
                }
                if (showToast) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Bookmarks are exported", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (showToast) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Bookmarks export failed", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
                if (e is SecurityException) {
                    config.bookmarkSyncUrl = ""
                }
            }
        }
    }

    fun createOpenBookmarkFileLauncher(activity: ComponentActivity) =
        IntentUnit.createResultLauncher(activity) { linkToBookmarkSyncFile(it) }

    fun createCreateBookmarkFileLauncher(activity: ComponentActivity) =
        IntentUnit.createResultLauncher(activity) { createBookmarkSyncFile(it) }

    fun handleBookmarkSync(
        forceUpload: Boolean = false,
    ) {
        if (forceUpload) {
            exportBookmarks(Uri.parse(config.bookmarkSyncUrl), false)
        } else {
            importBookmarks(Uri.parse(config.bookmarkSyncUrl))
        }
    }

    fun linkBookmarkSync(
        dialogManager: DialogManager,
        createBookmarkFileLauncher: ActivityResultLauncher<Intent>,
        openBookmarkFileLauncher: ActivityResultLauncher<Intent>,
    ) {
        dialogManager.showCreateOrOpenBookmarkFileDialog(
            { BrowserUnit.createBookmarkFilePicker(createBookmarkFileLauncher) },
            { BrowserUnit.openBookmarkFilePicker(openBookmarkFileLauncher) }
        )
    }

    private fun linkToBookmarkSyncFile(result: ActivityResult) {
        val uri = preprocessActivityResult(result) ?: return
        importBookmarks(uri)
        config.bookmarkSyncUrl = uri.toString()
    }

    private fun createBookmarkSyncFile(result: ActivityResult) {
        val uri = preprocessActivityResult(result) ?: return
        exportBookmarks(uri)
        config.bookmarkSyncUrl = uri.toString()
    }

    fun preprocessActivityResult(result: ActivityResult): Uri? {
        if (result.resultCode != FragmentActivity.RESULT_OK) return null
        val uri = result.data?.data ?: return null
        context.contentResolver
            .takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        return uri
    }

    fun exportDataToFileUri(uri: Uri, data: String) {
        val fileContent = data.toByteArray()

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(fileContent)
        }
    }

    private fun shareFile(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/html"
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, "Share via"))
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            addStreamToZip(zos, fis, entryName)
        }
    }

    private fun addStreamToZip(zos: ZipOutputStream, inputStream: InputStream, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        val buffer = ByteArray(1024)
        var length = inputStream.read(buffer)
        while (length > 0) {
            zos.write(buffer, 0, length)
            length = inputStream.read(buffer)
        }
        zos.closeEntry()
    }

    private fun resolveRestoreTarget(context: Context, entryName: String): File {
        return when {
            entryName.startsWith("databases/") ->
                File(DATABASE_PATH, entryName.removePrefix("databases/"))
            entryName.startsWith("shared_prefs/") ->
                File(SHARED_PREFS_PATH, entryName.removePrefix("shared_prefs/"))
            entryName.startsWith("$USER_EXTENSIONS_DIR/") ->
                File(context.filesDir, entryName)
            entryName == EXTENSIONS_INDEX_FILE ->
                File(context.filesDir, EXTENSIONS_INDEX_FILE)
            entryName.endsWith(".db") || entryName.contains("einkbro_db") ->
                File(DATABASE_PATH, entryName)
            else ->
                File(SHARED_PREFS_PATH, entryName)
        }
    }


    companion object {
        private const val DATABASE_PATH = "/data/data/info.plateaukao.einkbro/databases/"
        private const val SHARED_PREFS_PATH = "/data/data/info.plateaukao.einkbro/shared_prefs/"
        private const val USER_EXTENSIONS_DIR = "user_extensions"
        private const val EXTENSIONS_INDEX_FILE = "extensions_index.json"
    }
}

private fun List<Bookmark>.toJsonString(): String {
    val jsonArrays = JSONArray()
    this.map { it.toJsonObject() }.forEach { jsonArrays.put(it) }

    return jsonArrays.toString()
}

private fun Bookmark.toJsonObject(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("title", title)
        put("url", url)
        put("isDirectory", isDirectory)
        put("parent", parent)
        put("order", order)
    }

private fun JSONObject.toBookmark(): Bookmark =
    Bookmark(
        optString("title"),
        optString("url"),
        optBoolean("isDirectory"),
        optInt("parent"),
        optInt("order")
    ).apply { id = optInt("id") }

