package com.uasd.main

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

class DriveServiceHelper(private val driveService: Drive) {

    companion object {
        fun getDriveService(context: Context, account: GoogleSignInAccount): DriveServiceHelper {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = account.account
            
            val googleDriveService = Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory(),
                credential
            )
            .setApplicationName("UASD Assistant")
            .build()

            return DriveServiceHelper(googleDriveService)
        }
    }

    suspend fun createFolderIfNotExist(folderName: String): String {
        return withContext(Dispatchers.IO) {
            // Check if folder exists
            val query = "mimeType='application/vnd.google-apps.folder' and name='$folderName' and trashed=false"
            val result = driveService.files().list().setQ(query).execute()
            
            if (result.files.isNotEmpty()) {
                val id = result.files[0].id
                android.util.Log.d("DriveService", "Folder found: $id")
                id
            } else {
                // Create folder
                val fileMetadata = com.google.api.services.drive.model.File()
                fileMetadata.name = folderName
                fileMetadata.mimeType = "application/vnd.google-apps.folder"
                
                val file = driveService.files().create(fileMetadata)
                    .setFields("id")
                    .execute()
                android.util.Log.d("DriveService", "Folder created: ${file.id}")
                file.id
            }
        }
    }

    suspend fun uploadFile(localFile: File, mimeType: String, folderId: String?): String {
        return withContext(Dispatchers.IO) {
            val fileMetadata = com.google.api.services.drive.model.File()
            fileMetadata.name = localFile.name
            if (folderId != null) {
                fileMetadata.parents = listOf(folderId)
            }
            
            val fileContent = FileContent(mimeType, localFile)
            
            val file = driveService.files().create(fileMetadata, fileContent)
                .setFields("id")
                .execute()
            file.id
        }
    }
}
