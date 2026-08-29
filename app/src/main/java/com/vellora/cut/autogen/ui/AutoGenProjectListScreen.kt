package com.vellora.cut.autogen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vellora.cut.autogen.data.AutoGenProjectEntity
import com.vellora.cut.data.AppDatabase
import com.vellora.cut.ui.theme.*

@Composable
fun AutoGenProjectListScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onNewProject: () -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val projects by db.autoGenDao().observeProjects()
        .collectAsState(initial = emptyList())

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(text = "← Hub", color = TextSecondary, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "AUTO GENERATOR",
                    color = CyanPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) {
                    Text(text = "⚙️", fontSize = 16.sp)
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewProject,
                containerColor = CyanPrimary
            ) {
                Text(text = "+", fontSize = 24.sp, color = BackgroundDark)
            }
        }
    ) { padding ->
        if (projects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "کوئی پروجیکٹ موجود نہیں", color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "نیچے + دبا کر نیا شروع کریں", color = TextSecondary, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectRow(project = project, onClick = { onOpenProject(project.id) })
                }
                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: AutoGenProjectEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = project.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${project.resolution} · ${project.imageDurationSec}s/image · ${statusLabel(project.status)}",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
        Text(text = "›", color = CyanPrimary, fontSize = 20.sp)
    }
}

private fun statusLabel(status: String): String = when (status) {
    "draft" -> "Draft"
    "generating" -> "Generating"
    "ready" -> "Ready"
    "rendered" -> "Rendered"
    else -> status
}
