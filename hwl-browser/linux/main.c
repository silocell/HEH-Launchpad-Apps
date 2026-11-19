/* HWL Browser for Linux: a school-safe web browser (GTK 3 + WebKitGTK 4.1).
 * Every website you open (typed, searched, a link inside a page, or a pop-up) is checked first by
 * the Launchpad's AI safety check, unless it's a trusted learning site.
 *
 * Build: gcc main.c $(pkg-config --cflags --libs webkit2gtk-4.1 libsoup-3.0) -O2 -o hwl-browser
 */
#include <gtk/gtk.h>
#include <webkit2/webkit2.h>
#include <libsoup/soup.h>
#include <string.h>

#define CHECK_URL "https://hehs-live.duckdns.org/functions/v1/linkcheck"
#define ANON_KEY "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzkyODMyMjkwLCJleHAiOjIxMDgxOTIyOTB9.7gFhQa5RBwVtiOPLO7Qf2-aVEbkd5xgpLiN1J06Gybo"
#define HOME_DIR "/usr/share/hwl-browser"

static GtkWidget *window, *addr, *stack, *cover;
static WebKitWebView *web;
static SoupSession *soup;
static char *home_url;
static GHashTable *ok_urls, *bad_hosts;
static gboolean fullscreen = FALSE;
static char *start_link = NULL;

static const char *TRUSTED[] = { "wikipedia.org", "wikimedia.org", "bbc.co.uk", "civilinitiatives.org", "chausima.com",
  "kiddle.co", "nasa.gov", "nhm.ac.uk", "britannica.com", "natgeokids.com", "mathsisfun.com", "gov.uk", "scratch.mit.edu",
  "khanacademy.org", "developer.mozilla.org", "w3schools.com", "duckdns.org",
  "youtube.com", "youtu.be", "youtube-nocookie.com", "google.com", "google.co.uk", "office.com", "microsoft365.com", "office365.com", "live.com", "microsoftonline.com", "sharepoint.com", "onedrive.com", "microsoft.com", "cloud.microsoft", "github.com",
  "sparx-learning.com", "sparxmaths.uk", "sparxreader.com", "sparxmaths.com", "educake.co.uk", "senecalearning.com", "arbor.sc", "arbor-education.com", "wiktionary.org" };

static gboolean ends_with_host(const char *host, const char *o_) {
  size_t h = strlen(host), o = strlen(o_);
  return g_ascii_strcasecmp(host, o_) == 0 || (h > o + 1 && host[h - o - 1] == '.' && g_ascii_strcasecmp(host + h - o, o_) == 0);
}
/* a single YouTube video: checked by the AI (judged like a "15" rating) even though YouTube itself is trusted */
static gboolean is_video(const char *url) {
  GUri *u = g_uri_parse(url, G_URI_FLAGS_ENCODED, NULL);
  if (!u || !g_uri_get_host(u)) { if (u) g_uri_unref(u); return FALSE; }
  const char *h = g_uri_get_host(u), *p = g_uri_get_path(u);
  gboolean tube = ends_with_host(h, "youtube.com") || ends_with_host(h, "youtu.be") || ends_with_host(h, "youtube-nocookie.com");
  gboolean v = tube && (ends_with_host(h, "youtu.be") || g_str_has_prefix(p, "/watch") || g_str_has_prefix(p, "/shorts/") || g_str_has_prefix(p, "/embed/") || g_str_has_prefix(p, "/live/"));
  g_uri_unref(u);
  return v;
}
static gboolean trusted(const char *host) {
  size_t h = strlen(host);
  for (guint i = 0; i < G_N_ELEMENTS(TRUSTED); i++) {
    size_t o = strlen(TRUSTED[i]);
    if (g_ascii_strcasecmp(host, TRUSTED[i]) == 0) return TRUE;
    if (h > o + 1 && host[h - o - 1] == '.' && g_ascii_strcasecmp(host + h - o, TRUSTED[i]) == 0) return TRUE;
  }
  return FALSE;
}

static void blocked(const char *url, const char *why) {
  char *w = g_markup_escape_text(why, -1), *u = g_markup_escape_text(url, -1);
  char *html = g_strdup_printf("<!doctype html><body style=\"font-family:sans-serif;text-align:center;padding:70px 24px;color:#1d2433\">"
    "<div style=\"font-size:56px\">&#x1F6E1;&#xFE0F;</div><h2>Not opened</h2><p style=\"color:#5b6478\">%s</p>"
    "<p style=\"color:#9aa1b5;font-size:13px;word-break:break-all\">%s</p>"
    "<p><a href=\"%s\" style=\"color:#3b6cf0;font-weight:bold;text-decoration:none\">Back to HWL Search</a></p></body>", w, u, home_url);
  webkit_web_view_load_alternate_html(web, html, "about:blank", NULL);
  gtk_entry_set_text(GTK_ENTRY(addr), url);
  g_free(html); g_free(w); g_free(u);
}

typedef struct { char *url, *host; SoupMessage *msg; } Check;

static void checked(GObject *src, GAsyncResult *res, gpointer data) {
  Check *c = data;
  gboolean ok = FALSE;
  char *why = g_strdup("The safety check isn't answering right now, so this page stays closed. Try again in a minute.");
  GBytes *b = soup_session_send_and_read_finish(SOUP_SESSION(src), res, NULL);
  if (b) {
    gsize n; const char *body = g_bytes_get_data(b, &n);
    char *s = g_strndup(body ? body : "", n);
    GRegex *rok = g_regex_new("\"ok\"\\s*:\\s*true", 0, 0, NULL);
    ok = g_regex_match(rok, s, 0, NULL);
    g_regex_unref(rok);
    GRegex *rr = g_regex_new("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", 0, 0, NULL);
    GMatchInfo *mi;
    if (g_regex_match(rr, s, 0, &mi)) { char *raw = g_match_info_fetch(mi, 1); g_free(why); why = g_strcompress(raw); g_free(raw); }
    g_match_info_free(mi); g_regex_unref(rr);
    g_free(s); g_bytes_unref(b);
  }
  gtk_stack_set_visible_child_name(GTK_STACK(stack), "web");
  if (ok) { g_hash_table_add(ok_urls, g_strdup(c->url)); webkit_web_view_load_uri(web, c->url); }
  else {
    if (strstr(why, "isn't suitable") && !is_video(c->url)) g_hash_table_insert(bad_hosts, g_strdup(c->host), g_strdup(why));
    blocked(c->url, why);
  }
  g_free(why); g_free(c->url); g_free(c->host); g_object_unref(c->msg); g_free(c);
}

static void check_then_open(const char *url, const char *host) {
  gtk_stack_set_visible_child_name(GTK_STACK(stack), "cover");
  gtk_entry_set_text(GTK_ENTRY(addr), url);
  Check *c = g_new0(Check, 1);
  c->url = g_strdup(url); c->host = g_strdup(host);
  c->msg = soup_message_new("POST", CHECK_URL);
  SoupMessageHeaders *h = soup_message_get_request_headers(c->msg);
  soup_message_headers_append(h, "apikey", ANON_KEY);
  soup_message_headers_append(h, "Authorization", "Bearer " ANON_KEY);
  soup_message_headers_append(h, "x-hwl-app", "hwl-browser/1.0-linux");
  GString *j = g_string_new("{\"url\":\"");
  for (const char *p = url; *p; p++) { if (*p == '"' || *p == '\\') g_string_append_c(j, '\\'); g_string_append_c(j, *p); }
  g_string_append(j, "\",\"age\":15}");
  GBytes *body = g_string_free_to_bytes(j);
  soup_message_set_request_body_from_bytes(c->msg, "application/json", body);
  g_bytes_unref(body);
  soup_session_send_and_read_async(soup, c->msg, G_PRIORITY_DEFAULT, NULL, checked, c);
}

/* TRUE = go ahead now; FALSE = blocked, or being checked (it opens by itself if it passes). */
static gboolean allowed_now(const char *url) {
  if (!url || !*url) return FALSE;
  if (g_str_has_prefix(url, home_url) || g_str_has_prefix(url, "about:") || g_str_has_prefix(url, "data:")) return TRUE;
  GUri *u = g_uri_parse(url, G_URI_FLAGS_ENCODED, NULL);
  const char *scheme = u ? g_uri_get_scheme(u) : NULL;
  if (!u || !scheme || (g_ascii_strcasecmp(scheme, "https") && g_ascii_strcasecmp(scheme, "http")) || !g_uri_get_host(u)) {
    if (u) g_uri_unref(u);
    blocked(url, "HWL Browser only opens web pages.");
    return FALSE;
  }
  char *host = g_ascii_strdown(g_uri_get_host(u), -1);
  const char *path = g_uri_get_path(u), *query = g_uri_get_query(u);
  if ((ends_with_host(host, "google.com") || ends_with_host(host, "google.co.uk")) && g_strcmp0(path, "/search") == 0 &&
      !(query && (g_str_has_prefix(query, "safe=active") || strstr(query, "&safe=active")))) {   /* Google SafeSearch on */
    char *s2 = g_strconcat(url, query ? "&" : "?", "safe=active", NULL);
    webkit_web_view_load_uri(web, s2); g_free(s2); g_free(host); g_uri_unref(u);
    return FALSE;
  }
  g_uri_unref(u);
  gboolean go = g_hash_table_contains(ok_urls, url) || (!is_video(url) && trusted(host));
  if (!go) {
    const char *why = g_hash_table_lookup(bad_hosts, host);
    if (why) blocked(url, why); else check_then_open(url, host);
  }
  g_free(host);
  return go;
}

static gboolean decide(WebKitWebView *v, WebKitPolicyDecision *d, WebKitPolicyDecisionType type, gpointer _) {
  if (type == WEBKIT_POLICY_DECISION_TYPE_RESPONSE) return FALSE;
  WebKitNavigationAction *a = webkit_navigation_policy_decision_get_navigation_action(WEBKIT_NAVIGATION_POLICY_DECISION(d));
  const char *uri = webkit_uri_request_get_uri(webkit_navigation_action_get_request(a));
  if (type == WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) {   /* pop-ups open in this window, after the check */
    webkit_policy_decision_ignore(d);
    if (allowed_now(uri)) webkit_web_view_load_uri(web, uri);
    return TRUE;
  }
  if (g_str_has_prefix(uri, "about:")) return FALSE;              /* the "Not opened" page */
  if (!allowed_now(uri)) { webkit_policy_decision_ignore(d); return TRUE; }
  return FALSE;
}

static void show_address(void) {
  if (gtk_widget_has_focus(addr)) return;
  const char *url = webkit_web_view_get_uri(web);
  if (!url || g_str_has_prefix(url, "about:")) return;
  if (g_str_has_prefix(url, home_url)) {
    const char *q = strstr(url, "#q=");
    char *t = q ? g_uri_unescape_string(q + 3, NULL) : g_strdup("");
    gtk_entry_set_text(GTK_ENTRY(addr), t ? t : ""); g_free(t);
  } else gtk_entry_set_text(GTK_ENTRY(addr), url);
}
static void on_uri(GObject *o, GParamSpec *p, gpointer _) {
  show_address();
  /* YouTube changes video without loading a new page: check those too */
  const char *url = webkit_web_view_get_uri(web);
  if (url && is_video(url) && !g_hash_table_contains(ok_urls, url) &&
      g_strcmp0(gtk_stack_get_visible_child_name(GTK_STACK(stack)), "cover") != 0) {
    char *keep = g_strdup(url);
    webkit_web_view_load_uri(web, "about:blank");
    GUri *u = g_uri_parse(keep, G_URI_FLAGS_ENCODED, NULL);
    char *host = g_ascii_strdown(u && g_uri_get_host(u) ? g_uri_get_host(u) : "", -1);
    check_then_open(keep, host);
    g_free(host); if (u) g_uri_unref(u); g_free(keep);
  }
}
static void on_title(GObject *o, GParamSpec *p, gpointer _) {
  const char *t = webkit_web_view_get_title(web);
  char *s = (t && *t) ? g_strdup_printf("%s - HWL Browser", t) : g_strdup("HWL Browser");
  gtk_window_set_title(GTK_WINDOW(window), s); g_free(s);
}

static void go(GtkEntry *e, gpointer _) {
  char *t = g_strstrip(g_strdup(gtk_entry_get_text(e)));
  if (*t) {
    gtk_widget_grab_focus(GTK_WIDGET(web));
    if (!strchr(t, ' ') && strchr(t, '.')) {
      char *u = strstr(t, "://") ? g_strdup(t) : g_strconcat("https://", t, NULL);
      if (allowed_now(u)) webkit_web_view_load_uri(web, u);
      g_free(u);
    } else {
      char *q = g_uri_escape_string(t, NULL, FALSE), *u = g_strconcat(home_url, "#q=", q, NULL);
      webkit_web_view_load_uri(web, u); g_free(q); g_free(u);
    }
  }
  g_free(t);
}

static void back(GtkButton *b, gpointer _) { if (webkit_web_view_can_go_back(web)) webkit_web_view_go_back(web); }
#define NEW_TAB "https://chausima.com/"   /* start page, Home button and new tabs; searches still use home_url */
static void home(GtkButton *b, gpointer _) { webkit_web_view_load_uri(web, NEW_TAB); }
static void reload(GtkButton *b, gpointer _) { webkit_web_view_reload(web); }
static void toggle_full(void) { fullscreen = !fullscreen; if (fullscreen) gtk_window_fullscreen(GTK_WINDOW(window)); else gtk_window_unfullscreen(GTK_WINDOW(window)); }
static void full(GtkButton *b, gpointer _) { toggle_full(); }

static gboolean keys(GtkWidget *w, GdkEventKey *e, gpointer _) {
  if (e->keyval == GDK_KEY_F11) { toggle_full(); return TRUE; }
  if ((e->state & GDK_CONTROL_MASK) && (e->keyval == GDK_KEY_l || e->keyval == GDK_KEY_L)) { gtk_widget_grab_focus(addr); return TRUE; }
  if ((e->state & GDK_MOD1_MASK) && e->keyval == GDK_KEY_Left) { back(NULL, NULL); return TRUE; }
  return FALSE;
}

static GtkWidget *button(const char *icon, const char *tip, GCallback cb) {
  GtkWidget *b = gtk_button_new_from_icon_name(icon, GTK_ICON_SIZE_LARGE_TOOLBAR);
  gtk_button_set_relief(GTK_BUTTON(b), GTK_RELIEF_NONE);
  gtk_widget_set_tooltip_text(b, tip);
  gtk_widget_set_can_focus(b, FALSE);
  g_signal_connect(b, "clicked", cb, NULL);
  return b;
}

static char *find_home(void) {
  /* next to the program (AppImage / build folder), else the installed copy */
  char *self = g_file_read_link("/proc/self/exe", NULL), *dir = self ? g_path_get_dirname(self) : g_strdup(".");
  const char *tries[] = { "home.html", "../share/hwl-browser/home.html" };
  char *found = NULL;
  for (guint i = 0; i < G_N_ELEMENTS(tries) && !found; i++) {
    char *p = g_build_filename(dir, tries[i], NULL), *c = g_canonicalize_filename(p, NULL);
    if (g_file_test(c, G_FILE_TEST_EXISTS)) found = g_strdup(c);
    g_free(p); g_free(c);
  }
  if (!found) found = g_strdup(HOME_DIR "/home.html");
  char *uri = g_filename_to_uri(found, NULL, NULL);
  g_free(found); g_free(self); g_free(dir);
  return uri;
}

/* hwlbrowser://open?q=words searches, ?u=address opens a website (after the usual check) */
static gboolean open_link(const char *link) {
  if (!link) return FALSE;
  GUri *d = g_uri_parse(link, G_URI_FLAGS_ENCODED, NULL);
  if (!d) return FALSE;
  const char *sc = g_uri_get_scheme(d), *h = g_uri_get_host(d);
  if (!(g_strcmp0(sc, "hwlbrowser") == 0 || (g_strcmp0(sc, "https") == 0 && g_strcmp0(h, "net.chausima.com") == 0))) { g_uri_unref(d); return FALSE; }
  GHashTable *t = g_uri_get_query(d) ? g_uri_parse_params(g_uri_get_query(d), -1, "&", G_URI_PARAMS_WWW_FORM, NULL) : NULL;
  const char *q = t ? g_hash_table_lookup(t, "q") : NULL, *u = t ? g_hash_table_lookup(t, "u") : NULL;
  if (u && *u) {
    char *full = g_regex_match_simple("^https?://", u, G_REGEX_CASELESS, 0) ? g_strdup(u) : g_strconcat("https://", u, NULL);
    webkit_web_view_load_uri(web, home_url);
    if (allowed_now(full)) webkit_web_view_load_uri(web, full);
    g_free(full);
  } else if (q && *q) {
    char *e = g_uri_escape_string(q, NULL, FALSE), *s = g_strconcat(home_url, "#q=", e, NULL);
    webkit_web_view_load_uri(web, s); g_free(e); g_free(s);
  } else webkit_web_view_load_uri(web, NEW_TAB);
  if (t) g_hash_table_unref(t);
  g_uri_unref(d);
  return TRUE;
}

static void activate(GtkApplication *app, gpointer _) {
  if (window) { gtk_window_present(GTK_WINDOW(window)); return; }
  home_url = find_home();
  ok_urls = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, NULL);
  bad_hosts = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, g_free);
  soup = soup_session_new_with_options("timeout", 25, "user-agent", "HWLBrowser/1.0 (Linux)", NULL);

  window = gtk_application_window_new(app);
  gtk_window_set_title(GTK_WINDOW(window), "HWL Browser");
  gtk_window_set_icon_name(GTK_WINDOW(window), "org.civilinitiatives.hwlbrowser");
  gtk_window_set_default_size(GTK_WINDOW(window), 1200, 820);
  g_signal_connect(window, "key-press-event", G_CALLBACK(keys), NULL);

  GtkWidget *box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
  GtkWidget *bar = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
  gtk_container_set_border_width(GTK_CONTAINER(bar), 6);
  gtk_box_pack_start(GTK_BOX(bar), button("go-previous-symbolic", "Back", G_CALLBACK(back)), FALSE, FALSE, 0);
  gtk_box_pack_start(GTK_BOX(bar), button("go-home-symbolic", "Home", G_CALLBACK(home)), FALSE, FALSE, 0);
  addr = gtk_entry_new();
  gtk_entry_set_placeholder_text(GTK_ENTRY(addr), "Search or type a web address");
  gtk_entry_set_input_purpose(GTK_ENTRY(addr), GTK_INPUT_PURPOSE_URL);
  g_signal_connect(addr, "activate", G_CALLBACK(go), NULL);
  gtk_box_pack_start(GTK_BOX(bar), addr, TRUE, TRUE, 4);
  gtk_box_pack_start(GTK_BOX(bar), button("view-refresh-symbolic", "Reload", G_CALLBACK(reload)), FALSE, FALSE, 0);
  gtk_box_pack_start(GTK_BOX(bar), button("view-fullscreen-symbolic", "Full screen (F11)", G_CALLBACK(full)), FALSE, FALSE, 0);
  gtk_box_pack_start(GTK_BOX(box), bar, FALSE, FALSE, 0);

  WebKitWebContext *ctx = webkit_web_context_get_default();
  webkit_web_context_set_cache_model(ctx, WEBKIT_CACHE_MODEL_WEB_BROWSER);
  web = WEBKIT_WEB_VIEW(webkit_web_view_new());
  WebKitSettings *s = webkit_web_view_get_settings(web);
  webkit_settings_set_enable_developer_extras(s, FALSE);
  webkit_settings_set_allow_file_access_from_file_urls(s, FALSE);
  webkit_settings_set_media_playback_requires_user_gesture(s, TRUE);
  char *ua = g_strconcat(webkit_settings_get_user_agent(s), " HWLBrowser/1.0", NULL);
  webkit_settings_set_user_agent(s, ua); g_free(ua);
  g_signal_connect(web, "decide-policy", G_CALLBACK(decide), NULL);
  g_signal_connect(web, "notify::uri", G_CALLBACK(on_uri), NULL);
  g_signal_connect(web, "notify::title", G_CALLBACK(on_title), NULL);

  cover = gtk_label_new(NULL);
  gtk_label_set_markup(GTK_LABEL(cover), "<span size='x-large' weight='bold'>Checking this page is OK for school…</span>");
  stack = gtk_stack_new();
  gtk_stack_add_named(GTK_STACK(stack), GTK_WIDGET(web), "web");
  gtk_stack_add_named(GTK_STACK(stack), cover, "cover");
  gtk_box_pack_start(GTK_BOX(box), stack, TRUE, TRUE, 0);
  gtk_container_add(GTK_CONTAINER(window), box);
  gtk_widget_show_all(window);
  gtk_stack_set_visible_child_name(GTK_STACK(stack), "web");
  if (!open_link(start_link)) webkit_web_view_load_uri(web, NEW_TAB);
}

int main(int argc, char **argv) {
  GtkApplication *app = gtk_application_new("org.civilinitiatives.hwlbrowser", G_APPLICATION_FLAGS_NONE);
  g_signal_connect(app, "activate", G_CALLBACK(activate), NULL);
  if (argc > 1) start_link = argv[1];
  int r = g_application_run(G_APPLICATION(app), 1, argv);
  g_object_unref(app);
  return r;
}
