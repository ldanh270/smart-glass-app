package com.example.glassespro

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

class MyNotificationListenerService : NotificationListenerService() {

    // Hàm này được gọi khi có thông báo mới hoặc thông báo cũ cập nhật
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        processNotification(sbn)
    }

    // Một số máy Android gửi cập nhật thông báo qua sự kiện thay đổi ranking
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        super.onNotificationRankingUpdate(rankingMap)
        // Quét lại toàn bộ thông báo đang hiển thị để đảm bảo không bỏ lỡ cập nhật từ Google Maps
        try {
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName == "com.google.android.apps.maps") {
                    processNotification(sbn)
                }
            }
        } catch (e: Exception) {
            Log.e("MapsCrawler", "Error in ranking update: ${e.message}")
        }
    }

    private fun processNotification(sbn: StatusBarNotification?) {
        sbn?.let {
            if (it.packageName == "com.google.android.apps.maps") {
                val extras = it.notification.extras
                
                val title = extras.getCharSequence("android.title")?.toString() ?: ""
                val text = extras.getCharSequence("android.text")?.toString() ?: ""
                val subText = extras.getCharSequence("android.subText")?.toString() ?: ""
                val infoText = extras.getCharSequence("android.infoText")?.toString() ?: ""
                
                Log.d("MapsCrawler", "Process: title='$title', text='$text', subText='$subText', infoText='$infoText'")

                val json = JSONObject()
                json.put("packageName", it.packageName)
                json.put("type", "navigation")

                val distanceRegex = Regex(".*\\d+([.,]\\d+)?\\s*(m|km).*")
                
                var finalInstruction = ""
                var finalDistanceToTurn = ""

                // Kiểm tra tất cả các trường để tìm khoảng cách
                when {
                    distanceRegex.matches(title) -> {
                        finalDistanceToTurn = title
                        finalInstruction = text
                    }
                    distanceRegex.matches(text) && text.length < 15 -> {
                        finalDistanceToTurn = text
                        finalInstruction = title
                    }
                    distanceRegex.matches(infoText) -> {
                        finalDistanceToTurn = infoText
                        finalInstruction = if (title.isNotEmpty()) title else text
                    }
                    else -> {
                        finalInstruction = if (title.isNotEmpty()) title else text
                        finalDistanceToTurn = ""
                    }
                }

                json.put("instruction", finalInstruction)
                json.put("distance_to_turn", finalDistanceToTurn)

                if (finalInstruction.contains(" vào ")) {
                    val parts = finalInstruction.split(" vào ")
                    json.put("action", parts[0])
                    json.put("road", parts[1])
                } else {
                    json.put("action", finalInstruction)
                    json.put("road", "")
                }

                if (subText.isNotEmpty()) {
                    val infoParts = subText.split(" · ")
                    json.put("time_remaining", infoParts.getOrNull(0) ?: "")
                    json.put("distance_total", infoParts.getOrNull(1) ?: "")
                    json.put("arrival_time", infoParts.getOrNull(2) ?: "")
                }

                json.put("timestamp", System.currentTimeMillis())

                val intent = Intent("com.example.glassespro.NOTIFICATION_LISTENER")
                intent.putExtra("notification_json", json.toString())
                sendBroadcast(intent)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == "com.google.android.apps.maps") {
            val intent = Intent("com.example.glassespro.NOTIFICATION_LISTENER")
            val json = JSONObject()
            json.put("type", "navigation_ended")
            intent.putExtra("notification_json", json.toString())
            sendBroadcast(intent)
        }
    }
}
