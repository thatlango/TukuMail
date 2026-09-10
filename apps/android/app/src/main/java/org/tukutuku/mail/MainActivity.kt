package org.tukutuku.mail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val Ink = Color(0xFF20211F)
private val Paper = Color(0xFFF7F7F3)
private val Brand = Color(0xFF30463A)
private val BrandSoft = Color(0xFFE5EEE7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TukuTheme { TukuMailApp() } }
    }
}

@Composable
fun TukuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Brand,
            onPrimary = Color.White,
            background = Paper,
            surface = Color.White,
            onSurface = Ink,
            surfaceVariant = Color(0xFFF0F1EC),
        ),
        content = content,
    )
}

@Composable
fun TukuMailApp() {
    val api = remember { MailApi() }
    var session by remember { mutableStateOf<MailSession?>(null) }
    if (session == null) {
        LoginScreen(api) { session = it }
    } else {
        MailboxScreen(api, session!!) { session = null }
    }
}

@Composable
fun LoginScreen(api: MailApi, onLogin: (MailSession) -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(48.dp).background(Ink, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("T", color = Color.White, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(48.dp))
            Text("TUKUMAIL", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Your work email,\nwithout the clutter.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text("Sign in with your organisation email address.", color = Color.Gray)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email address") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            if (error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = ""
                        runCatching { api.login(email.trim(), password) }
                            .onSuccess(onLogin)
                            .onFailure { error = it.message ?: "Could not sign in" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (busy) "Signing in…" else "Sign in", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(api: MailApi, session: MailSession, onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mail by remember { mutableStateOf<List<MailSummary>>(emptyList()) }
    var detail by remember { mutableStateOf<MailDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var compose by remember { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<MailDetail?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { api.inbox(session.token) }
                .onSuccess { mail = it }
                .onFailure { error = it.message ?: "Sync failed" }
            loading = false
        }
    }

    LaunchedEffect(session.token) { refresh() }

    Scaffold(
        containerColor = Paper,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Inbox", fontWeight = FontWeight.Bold)
                        Text(session.email, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                    IconButton(onClick = onSignOut) { Icon(Icons.Outlined.AccountCircle, "Account") }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Ink, contentColor = Color.White) {
                Nav(Icons.Outlined.Inbox, "Inbox", true) {}
                Nav(Icons.Outlined.Search, "Search") {}
                Nav(Icons.Outlined.AddCircle, "Compose") { replyTo = null; compose = true }
                Nav(Icons.Outlined.Send, "Sent") {}
                Nav(Icons.Outlined.Menu, "More") {}
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { replyTo = null; compose = true },
                containerColor = Brand,
                contentColor = Color.White,
            ) { Icon(Icons.Outlined.Edit, "Compose") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading && mail.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                mail.isEmpty() -> EmptyInbox(Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(mail, key = { it.id }) { message ->
                        MailRow(message) {
                            scope.launch {
                                loading = true
                                runCatching { api.message(session.token, message.id) }
                                    .onSuccess { detail = it }
                                    .onFailure { error = it.message ?: "Could not open message" }
                                loading = false
                            }
                        }
                    }
                }
            }
            if (error.isNotBlank()) {
                AssistChip(
                    onClick = { error = "" },
                    label = { Text(error, maxLines = 1) },
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                )
            }
        }
    }

    detail?.let { message ->
        MessageDialog(
            message = message,
            onClose = { detail = null },
            onReply = {
                replyTo = message
                detail = null
                compose = true
            },
        )
    }

    if (compose) {
        ComposeDialog(
            api = api,
            session = session,
            replyTo = replyTo,
            onClose = { compose = false; replyTo = null },
            onSent = { refresh() },
        )
    }
}

@Composable
fun MailRow(message: MailSummary, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Avatar(message.from)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(
                        clean(message.from),
                        Modifier.weight(1f),
                        fontWeight = if (!message.read) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(shortDate(message.receivedAt), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                Text(
                    message.subject,
                    fontWeight = if (!message.read) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    message.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = Color(0xFFE8E9E4))
    }
}

@Composable
fun Avatar(value: String) {
    Box(
        Modifier.size(40.dp).background(if (value.hashCode() % 2 == 0) BrandSoft else Color(0xFFF0ECE4), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(value), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EmptyInbox(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).background(BrandSoft, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Done, contentDescription = null, tint = Brand)
        }
        Spacer(Modifier.height(14.dp))
        Text("You're all caught up", fontWeight = FontWeight.Bold)
        Text("New mail will appear here.", color = Color.Gray)
    }
}

@Composable
fun MessageDialog(message: MailDetail, onClose: () -> Unit, onReply: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onReply) {
                Icon(Icons.Outlined.Reply, null)
                Spacer(Modifier.width(6.dp))
                Text("Reply")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text(message.subject, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(message.from)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(clean(message.from), fontWeight = FontWeight.SemiBold)
                        Text("to ${message.to.joinToString()}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
                Spacer(Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(Modifier.height(18.dp))
                Text(message.bodyText.ifBlank { "This message has no plain-text content." })
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
fun ComposeDialog(
    api: MailApi,
    session: MailSession,
    replyTo: MailDetail?,
    onClose: () -> Unit,
    onSent: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var to by remember(replyTo?.id) { mutableStateOf(replyTo?.let { cleanAddress(it.from) } ?: "") }
    var cc by remember(replyTo?.id) { mutableStateOf("") }
    var subject by remember(replyTo?.id) {
        mutableStateOf(replyTo?.subject?.let { if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it" } ?: "")
    }
    var body by remember(replyTo?.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Button(
                enabled = !busy && to.isNotBlank() && subject.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { api.send(session.token, to, cc, subject, body) }
                            .onSuccess { onClose(); onSent() }
                            .onFailure { error = it.message ?: "Send failed" }
                        busy = false
                    }
                },
            ) {
                Icon(Icons.Outlined.Send, null)
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "Sending…" else "Send")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text(if (replyTo == null) "New message" else "Reply", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(to, { to = it }, label = { Text("To") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(cc, { cc = it }, label = { Text("Cc") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth().height(180.dp))
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
fun RowScope.Nav(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, label) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color.White,
            selectedTextColor = Color.White,
            unselectedIconColor = Color(0xFFC7C9C3),
            unselectedTextColor = Color(0xFFC7C9C3),
            indicatorColor = Color(0xFF3B3D39),
        ),
    )
}

private fun clean(value: String) = value.replace(Regex("<.*?>"), "").trim().trim('"')
private fun cleanAddress(value: String) = Regex("<([^>]+)>").find(value)?.groupValues?.get(1) ?: value.trim()
private fun initials(value: String) = clean(value).split(" ", "@").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
private fun shortDate(value: String) = runCatching { java.time.OffsetDateTime.parse(value).toLocalDate().toString().substring(5) }.getOrDefault("")
