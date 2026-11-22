package ch.ripple.app.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.ripple.core.Correction
import ch.ripple.core.InputState

@Composable
fun ExploreScreen(session: RoutesSession, nav: PhoneNav, voice: VoiceState, platform: PhonePlatform) {
    val list = rememberLazyListState()
    val turns = session.turns
    LaunchedEffect(turns.size, session.pending) { if (turns.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }
    Column(Modifier.fillMaxSize()) {
        Header(session)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            session.loadProblem?.let { p -> item { Notice("$p You can start a new session; the old file is kept aside, not deleted.", "Start a new session") { session.startFresh() } } }
            session.saveProblem?.let { p -> item { Notice(p) } }
            if (turns.isEmpty()) item { Welcome(session, nav) }
            items(turns) { t -> TurnView(t, session, nav) }
            session.pending?.let { p -> item { PendingCard(p, session) } }
        }
        val active = session.activeDecision
        if (active != null && session.interview == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Talking about: ${active.title}", style = Ty.Small, modifier = Modifier.weight(1f))
                LinkText("Open") { session.open(active.id); nav.openDecision = active.id; nav.space = Space.ROUTES }
            }
        }
        Composer(voice, if (session.interview != null) "Your answer" else "Ask “What if…”") { text, via -> session.handle(text, via) }
    }
}

@Composable
private fun Header(session: RoutesSession) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RoutesMark()
        Spacer(Modifier.width(8.dp))
        Text("Routes", style = Ty.Strong, modifier = Modifier.weight(1f))
        session.profile?.let { Text(if (it.fictional) "${it.name} · fictional example" else it.name, style = Ty.Small) }
    }
}

@Composable
private fun Welcome(session: RoutesSession, nav: PhoneNav) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 32.dp)) {
        val d = session.activeDecision
        if (d != null) {
            Text("Welcome back.", style = Ty.Title)
            Text("You were exploring “${d.title}”. Ask about it here, or open it in My Routes.", style = Ty.Body)
            Secondary("Open “${d.title}”") { nav.openDecision = d.id; nav.space = Space.ROUTES }
        } else {
            Text("What decision are you thinking about?", style = Ty.Title)
            Text("Ask “What if…”, by voice or typing. Say “about” or a range when you're not sure of an amount, or “not sure”.", style = Ty.Body.copy(color = Tk.Ink2))
            if (session.profile == null) LinkText("Try with an example person (Alex, fictional)") { session.startExample() }
        }
        Text("Routes explores consequences; it doesn't decide for you or give investment advice.", style = Ty.Small)
        Legal()
    }
}

@Composable
private fun TurnView(t: Turn, session: RoutesSession, nav: PhoneNav) {
    when (t) {
        is Turn.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(t.text + if (t.via == Via.VOICE) "  🎙" else "", style = Ty.Body,
                modifier = Modifier.widthIn(max = 300.dp).background(Tk.Chalk, Tk.R).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        is Turn.Routes -> Text(t.text, style = Ty.Body)
        is Turn.Finding -> {
            val exists = session.decisions.any { it.id == t.decisionId }
            val hypothetical = t.text.startsWith("What-if")
            Column(
                Modifier.fillMaxWidth().background(if (hypothetical) Tk.White else Tk.Gold, Tk.Card)
                    .then(if (hypothetical) Modifier.border(1.dp, Tk.BronzeBar, Tk.Card) else Modifier)
                    .clickable(enabled = exists, role = Role.Button) {
                        session.open(t.decisionId); nav.openDecision = t.decisionId; nav.space = Space.ROUTES
                    }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(t.text, style = Ty.Body)
                if (exists) Text("Open in My Routes ›", style = Ty.Label.copy(color = Tk.LinkRed))
            }
        }
    }
}

@Composable
fun PendingCard(p: Pending, session: RoutesSession) {
    val canTry = (p.correction as? Correction.AnswerInput)?.value is InputState.Exact
    Column(Modifier.fillMaxWidth().border(1.5.dp, Tk.Ink, Tk.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Heard: ${p.summary}", style = Ty.Strong, modifier = Modifier.weight(1f))
            Text("not saved", style = Ty.Small)
        }
        Text("If this is real: ${changeText(p.before, p.after)}", style = Ty.Body)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canTry) Secondary("Just trying", Modifier.weight(1f)) { session.tryPending() }
            Primary("That's real, save", Modifier.weight(1f)) { session.confirmPending() }
        }
        LinkText("Cancel") { session.dismissPending() }
    }
}
