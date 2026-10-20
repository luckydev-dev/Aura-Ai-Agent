package com.example.core.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.example.core.agent.ScreenXmlParser
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraNotificationListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.net.URLEncoder
import java.util.Locale

class CommTools(
    private val context: Context,
    private val rootManager: RootManager,
    private val preferences: AuraPreferences
) {

    @SuppressLint("Range")
    fun resolveContactNumber(query: String): Pair<String, String>? {
        val cleanQuery = query.trim()
        // If already numeric/phone format
        if (cleanQuery.matches(Regex("""^\+?[0-9\s\-]{7,16}$"""))) {
            return Pair(cleanQuery.replace(Regex("""[\s\-]"""), ""), cleanQuery)
        }

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$cleanQuery%"),
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val number = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                return Pair(name, number.replace(Regex("""[\s\-]"""), ""))
            }
        }
        return null
    }

    val sendWhatsappTool = object : Tool {
        override val name = "send_whatsapp"
        override val description = "Sends a WhatsApp message to a contact name or phone number."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "contact_or_number" to mapOf("type" to "string", "description" to "The contact name or full phone number with country code."),
                "message" to mapOf("type" to "string", "description" to "The message text to send.")
            ),
            "required" to listOf("contact_or_number", "message")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val target = args["contact_or_number"]?.toString() ?: return ToolExecutionResult(false, "Missing contact")
            val message = args["message"]?.toString() ?: return ToolExecutionResult(false, "Missing message")

            val contactInfo = resolveContactNumber(target)
            val phone = contactInfo?.second ?: target.replace(Regex("""[^\d+]"""), "")

            val isBusiness = preferences.isWhatsappBusiness.first()
            val pkg = if (isBusiness) "com.whatsapp.w4b" else "com.whatsapp"

            val encodedMsg = URLEncoder.encode(message, "UTF-8")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encodedMsg")

            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                // Fallback launch via am start
                rootManager.execute("am start -a android.intent.action.VIEW -d \"$uri\" $pkg")
            }

            // Wait for WhatsApp to open and load the message in the input field
            delay(2200)

            // Look for WhatsApp send button via uiautomator
            val dumpRes = rootManager.execute("uiautomator dump /sdcard/ui_wa.xml && cat /sdcard/ui_wa.xml")
            val nodes = ScreenXmlParser.parse(dumpRes.stdout)
            val sendButton = nodes.find {
                it.resourceId == "send" ||
                it.contentDescription.equals("Send", ignoreCase = true) ||
                it.contentDescription.contains("भेजें") // Hindi locale support
            }

            return if (sendButton != null) {
                rootManager.execute("input tap ${sendButton.centerX} ${sendButton.centerY}")
                ToolExecutionResult(true, "Sent WhatsApp message to ${contactInfo?.first ?: phone}")
            } else {
                // Fallback: enter key event or standard send button location on right edge
                rootManager.execute("input keyevent 66") // KEYCODE_ENTER
                ToolExecutionResult(true, "Opened chat with message prefilled and tapped send.")
            }
        }
    }

    val sendSmsTool = object : Tool {
        override val name = "send_sms"
        override val description = "Sends a standard SMS text message."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "contact" to mapOf("type" to "string", "description" to "The contact name or phone number."),
                "text" to mapOf("type" to "string", "description" to "The message body.")
            ),
            "required" to listOf("contact", "text")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val contact = args["contact"]?.toString() ?: return ToolExecutionResult(false, "Missing contact")
            val text = args["text"]?.toString() ?: return ToolExecutionResult(false, "Missing text")
            val info = resolveContactNumber(contact)
            val destination = info?.second ?: contact

            return try {
                val smsManager = SmsManager.getDefault()
                val parts = smsManager.divideMessage(text)
                smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
                ToolExecutionResult(true, "SMS sent to ${info?.first ?: destination}")
            } catch (e: Exception) {
                // Fallback via root service call
                val res = rootManager.execute("service call isms 7 i32 0 s16 \"com.android.mms\" s16 \"$destination\" s16 \"null\" s16 \"$text\"")
                if (res.success) {
                    ToolExecutionResult(true, "SMS sent via root to $destination")
                } else {
                    ToolExecutionResult(false, "Failed to send SMS: ${e.message}")
                }
            }
        }
    }

    val makeCallTool = object : Tool {
        override val name = "make_call"
        override val description = "Makes a phone call to a contact or phone number."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "contact_or_number" to mapOf("type" to "string", "description" to "The contact name or phone number.")
            ),
            "required" to listOf("contact_or_number")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val target = args["contact_or_number"]?.toString() ?: return ToolExecutionResult(false, "Missing phone number")
            val info = resolveContactNumber(target)
            val number = info?.second ?: target

            val res = rootManager.execute("am start -a android.intent.action.CALL -d tel:${Uri.encode(number)}")
            return if (res.success) {
                ToolExecutionResult(true, "Calling ${info?.first ?: number}")
            } else {
                ToolExecutionResult(false, "Could not initiate call: ${res.stderr}")
            }
        }
    }

    val answerCallTool = object : Tool {
        override val name = "answer_call"
        override val description = "Answers an incoming phone call."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("input keyevent 5") // KEYCODE_CALL
            return ToolExecutionResult(res.success, if (res.success) "Answered incoming call" else res.stderr)
        }
    }

    val endCallTool = object : Tool {
        override val name = "end_call"
        override val description = "Ends or hangs up the active phone call."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("input keyevent 6") // KEYCODE_ENDCALL
            return ToolExecutionResult(res.success, if (res.success) "Ended call" else res.stderr)
        }
    }

    @SuppressLint("Range")
    val readSmsTool = object : Tool {
        override val name = "read_sms"
        override val description = "Reads recent received SMS text messages."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "count" to mapOf("type" to "integer", "description" to "Number of SMS messages to read (default 5).")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val count = (args["count"] as? Number)?.toInt() ?: 5
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                null,
                null,
                "date DESC LIMIT $count"
            )

            val messages = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(it.getColumnIndex("address")) ?: "Unknown"
                    val body = it.getString(it.getColumnIndex("body")) ?: ""
                    messages.add("From: $address | Text: $body")
                }
            }

            return if (messages.isNotEmpty()) {
                ToolExecutionResult(true, messages.joinToString("\n---\n"))
            } else {
                ToolExecutionResult(true, "No recent SMS messages found.")
            }
        }
    }

    @SuppressLint("Range")
    val readCallLogTool = object : Tool {
        override val name = "read_call_log"
        override val description = "Reads recent incoming, outgoing, or missed calls."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "count" to mapOf("type" to "integer", "description" to "Number of call records to fetch (default 5).")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val count = (args["count"] as? Number)?.toInt() ?: 5
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT $count"
            )

            val records = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndex(CallLog.Calls.CACHED_NAME))
                    val number = it.getString(it.getColumnIndex(CallLog.Calls.NUMBER)) ?: ""
                    val type = when (it.getInt(it.getColumnIndex(CallLog.Calls.TYPE))) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Call"
                    }
                    records.add("$type: ${name ?: number}")
                }
            }

            return if (records.isNotEmpty()) {
                ToolExecutionResult(true, records.joinToString("\n"))
            } else {
                ToolExecutionResult(true, "No call logs found.")
            }
        }
    }

    val readNotificationsTool = object : Tool {
        override val name = "read_notifications"
        override val description = "Reads currently posted notifications on the device."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "count" to mapOf("type" to "integer", "description" to "Number of notifications to read (default 5)."),
                "app_filter" to mapOf("type" to "string", "description" to "Optional app name filter.")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val count = (args["count"] as? Number)?.toInt() ?: 5
            val filter = args["app_filter"]?.toString()?.lowercase(Locale.ROOT) ?: ""
            val notifs = AuraNotificationListener.getActiveNotificationsCached()
                .filter { if (filter.isBlank()) true else it.appName.lowercase(Locale.ROOT).contains(filter) }
                .take(count)

            return if (notifs.isNotEmpty()) {
                val formatted = notifs.joinToString("\n") {
                    "[${it.appName}] ${it.title}: ${it.text} (id: ${it.key})"
                }
                ToolExecutionResult(true, formatted)
            } else {
                ToolExecutionResult(true, "No active notifications matching.")
            }
        }
    }

    val replyToNotificationTool = object : Tool {
        override val name = "reply_to_notification"
        override val description = "Sends an inline reply to a notification."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "notification_id" to mapOf("type" to "string", "description" to "The key/id of the notification."),
                "message" to mapOf("type" to "string", "description" to "The reply message.")
            ),
            "required" to listOf("notification_id", "message")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val id = args["notification_id"]?.toString() ?: return ToolExecutionResult(false, "Missing notification id")
            val message = args["message"]?.toString() ?: return ToolExecutionResult(false, "Missing message")
            val success = AuraNotificationListener.replyToNotification(id, message)
            return if (success) {
                ToolExecutionResult(true, "Replied successfully.")
            } else {
                ToolExecutionResult(false, "Notification does not support inline reply or was dismissed.")
            }
        }
    }

    val dismissNotificationTool = object : Tool {
        override val name = "dismiss_notification"
        override val description = "Dismisses a notification."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "notification_id" to mapOf("type" to "string", "description" to "The key of the notification.")
            ),
            "required" to listOf("notification_id")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val id = args["notification_id"]?.toString() ?: return ToolExecutionResult(false, "Missing id")
            val success = AuraNotificationListener.dismissNotification(id)
            return ToolExecutionResult(success, if (success) "Notification dismissed" else "Could not find notification")
        }
    }

    @SuppressLint("Range")
    val searchContactsTool = object : Tool {
        override val name = "search_contacts"
        override val description = "Searches contacts by name."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "Contact name to search.")
            ),
            "required" to listOf("query")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["query"]?.toString() ?: return ToolExecutionResult(false, "Missing query")
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                null
            )

            val results = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                    val number = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    results.add("$name: $number")
                }
            }

            return if (results.isNotEmpty()) {
                ToolExecutionResult(true, results.joinToString("\n"))
            } else {
                ToolExecutionResult(true, "No contacts found matching '$query'")
            }
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        sendWhatsappTool, sendSmsTool, makeCallTool, answerCallTool, endCallTool,
        readSmsTool, readCallLogTool, readNotificationsTool, replyToNotificationTool,
        dismissNotificationTool, searchContactsTool
    )
}
