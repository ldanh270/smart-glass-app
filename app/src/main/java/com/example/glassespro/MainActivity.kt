package com.example.glassespro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.glassespro.ui.theme.GlassesProTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel()
        setContent {
            GlassesProTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NotificationDisplay(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Test Channel"
            val channel = NotificationChannel("test_channel", name, NotificationManager.IMPORTANCE_DEFAULT)
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

@Composable
fun NotificationDisplay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val rawLogs = remember { mutableStateListOf<String>() }
    val filteredLogs = remember { mutableStateListOf<String>() }
    val pushedDataLogs = remember { mutableStateListOf<String>() }
    var navInfo by remember { mutableStateOf<NavigationInfo?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var serverStatus by remember { mutableStateOf("Đang kiểm tra...") }
    var lastServerResponse by remember { mutableStateOf("") }
    
    val client = remember { OkHttpClient() }
    val serverUrl = "http://15.134.231.82:8080/nav"

    fun checkInitialConnection() {
        val request = Request.Builder().url(serverUrl).get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                serverStatus = "Lỗi kết nối"
                lastServerResponse = "GET failed: ${e.message}"
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    serverStatus = "Đã kết nối OK"
                    lastServerResponse = "GET success: Server sẵn sàng"
                } else {
                    serverStatus = "Server phản hồi lỗi"
                    lastServerResponse = "GET code: ${response.code}"
                }
                response.close()
            }
        })
    }

    fun pushToServer(jsonStr: String) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonStr.toRequestBody(mediaType)
        val request = Request.Builder().url(serverUrl).put(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                serverStatus = "Lỗi đẩy data"
                lastServerResponse = "PUSH failed: ${e.message}"
            }
            override fun onResponse(call: Call, response: Response) {
                serverStatus = if (response.isSuccessful) "Đã PUSH OK" else "Lỗi PUSH: ${response.code}"
                lastServerResponse = "PUSH Response code: ${response.code}"
                if (response.isSuccessful) {
                    pushedDataLogs.add(0, jsonStr)
                }
                response.close()
            }
        })
    }

    LaunchedEffect(Unit) { checkInitialConnection() }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val jsonString = intent?.getStringExtra("notification_json")
                if (jsonString != null) {
                    rawLogs.add(0, jsonString)
                    try {
                        val json = JSONObject(jsonString)
                        if (json.optString("type") == "navigation") {
                            val compactJson = JSONObject().apply {
                                put("action", json.optString("action"))
                                put("road", json.optString("road"))
                                put("dist", json.optString("distance_to_turn"))
                                put("time", json.optString("time_remaining"))
                                put("total", json.optString("distance_total"))
                            }
                            val compactStr = compactJson.toString()
                            filteredLogs.add(0, compactJson.toString(2))
                            pushToServer(compactStr)

                            navInfo = NavigationInfo(
                                action = json.optString("action"),
                                road = json.optString("road"),
                                distanceToTurn = json.optString("distance_to_turn"),
                                timeRemaining = json.optString("time_remaining"),
                                distanceTotal = json.optString("distance_total"),
                                arrivalTime = json.optString("arrival_time")
                            )
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }
        val filter = IntentFilter("com.example.glassespro.NOTIFICATION_LISTENER")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // --- HUD HIỂN THỊ CHÍNH ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF004D40)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (navInfo != null) {
                    Text(navInfo?.action ?: "", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White)
                    if (navInfo?.road?.isNotEmpty() == true) Text("vào ${navInfo?.road}", fontSize = 16.sp, color = Color(0xFFB2DFDB))
                    if (navInfo?.distanceToTurn?.isNotEmpty() == true) Text(navInfo?.distanceToTurn ?: "", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4DB6AC))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color(0x33FFFFFF))
                    Text("${navInfo?.timeRemaining} • ${navInfo?.distanceTotal}", fontSize = 14.sp, color = Color.White)
                } else {
                    Text("Chưa có dữ liệu dẫn đường...", color = Color(0xFF80CBC4))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- TABS ---
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) { Text("Raw", modifier = Modifier.padding(10.dp)) }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) { Text("Filtered", modifier = Modifier.padding(10.dp)) }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) { Text("Server", modifier = Modifier.padding(10.dp)) }
        }

        Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            when (selectedTab) {
                0 -> LogList(rawLogs)
                1 -> LogList(filteredLogs)
                2 -> ServerInfo(serverStatus, lastServerResponse, serverUrl, pushedDataLogs) { checkInitialConnection() }
            }
        }
    }
}

@Composable
fun LogList(logs: List<String>) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        logs.forEach { content ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF263238))
            ) {
                Text(text = content, modifier = Modifier.padding(10.dp), fontSize = 11.sp, color = Color(0xFFECEFF1), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun ServerInfo(status: String, lastResponse: String, url: String, pushedData: List<String>, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("CẤU HÌNH SERVER (PUT/PUSH)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("URL: $url", fontSize = 14.sp)
        
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kết nối: ", fontWeight = FontWeight.Bold)
            val dotColor = when {
                status.contains("OK") -> Color.Green
                status.contains("Lỗi") -> Color.Red
                else -> Color.Gray
            }
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
            Spacer(modifier = Modifier.width(8.dp))
            Text(status)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Kiểm tra lại kết nối (GET)") }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("LỊCH SỬ PUSH DỮ LIỆU:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (pushedData.isEmpty()) {
            Text("Chưa có dữ liệu nào được push.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
        }
        pushedData.take(5).forEach { data ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)) // Màu xanh lá nhạt cho dữ liệu đã gửi
            ) {
                Text(text = data, modifier = Modifier.padding(8.dp), fontSize = 11.sp, color = Color(0xFF2E7D32), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Phản hồi cuối cùng từ Server:", fontSize = 12.sp, color = Color.Gray)
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEEEEEE))
        ) {
            Text(text = lastResponse.ifEmpty { "Chưa có phản hồi" }, modifier = Modifier.padding(8.dp), fontSize = 12.sp)
        }
    }
}

data class NavigationInfo(val action: String, val road: String, val distanceToTurn: String, val timeRemaining: String, val distanceTotal: String, val arrivalTime: String)

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(pkgName)
}
