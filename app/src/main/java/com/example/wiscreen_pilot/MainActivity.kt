package com.example.wiscreen_pilot

import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Button
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.example.wiscreen_pilot.ui.theme.Wiscreen_pilotTheme
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Wiscreen_pilotTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var name by remember { mutableStateOf("") }
    var isGreetingVisible by remember { mutableStateOf(false) }
    var isVideoVisible by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (!isGreetingVisible) {
            // 输入框
            TextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("请输入名字") },
                modifier = Modifier.fillMaxWidth()
            )
            // 按钮
            Button(onClick = {
                isGreetingVisible = true
            }) {
                Text("提交")
            }
        }

        if (isGreetingVisible) {
            Text("你好，$name，欢迎！", modifier = Modifier.padding(top = 16.dp))
            Button(onClick = {
                isVideoVisible = true
            }) {
                Text("点击观看学习视频")
            }
        }

        if (isVideoVisible) {
            VideoPlayer()
        }
    }
}

@Composable
fun VideoPlayer() {
    val context = LocalContext.current
    val videoPath = "android.resource://${context.packageName}/${R.raw.video}" // 替换为你的视频文件名
    val videoUri: Uri = Uri.parse(videoPath)

    VideoView(context).apply {
        setVideoURI(videoUri)
        start()
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewMainScreen() {
    Wiscreen_pilotTheme {
        MainScreen()
    }
}
