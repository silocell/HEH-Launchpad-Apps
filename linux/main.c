/* Homework Launchpad for Linux (Ubuntu, Debian and friends).
 * © 2026 silocell. All rights reserved.
 *
 * A small native GTK app showing the Launchpad in WebKitGTK - the same pages
 * and design as the website, in its own window with its own icon.
 *
 * Sign-in: Microsoft (and a school's own sign-in pages) work inside the app.
 * Google refuses app web views, so Google sign-in opens in the normal
 * browser; the site then hands the sign-in back as hehlaunchpad://login#...
 * which the desktop passes to this app (see the .desktop file), and we open
 * ?appsession=1#at=..&rt=.. here. One window only: GtkApplication passes a
 * second launch's link to the running copy.
 *
 * Build: gcc main.c $(pkg-config --cflags --libs webkit2gtk-4.1) -O2 -o homework-launchpad
 */
#include <gtk/gtk.h>
#include <webkit2/webkit2.h>
#include <string.h>

#define HOME_URL "https://civilinitiatives.org/"

static GtkWidget *window = NULL;
static WebKitWebView *web = NULL;
static gboolean signing_in = FALSE;
static gboolean fullscreen = FALSE;

static gboolean is_ours(const char *host) {
  if (!host) return FALSE;
  const char *ours[] = { "civilinitiatives.org", "hehs-live.duckdns.org" };
  for (guint i = 0; i < G_N_ELEMENTS(ours); i++) {
    size_t h = strlen(host), o = strlen(ours[i]);
    if (g_strcmp0(host, ours[i]) == 0) return TRUE;
    if (h > o + 1 && host[h - o - 1] == '.' && strcmp(host + h - o, ours[i]) == 0) return TRUE;
  }
  return FALSE;
}

static void open_outside(const char *uri) {
  GError *err = NULL;
  if (!g_app_info_launch_default_for_uri(uri, NULL, &err)) {
    g_warning("couldn't open %s: %s", uri, err ? err->message : "?");
    g_clear_error(&err);
  }
}

static char *query_param(GUri *u, const char *key) {
  const char *q = g_uri_get_query(u);
  if (!q) return NULL;
  GHashTable *t = g_uri_parse_params(q, -1, "&", G_URI_PARAMS_NONE, NULL);
  if (!t) return NULL;
  char *v = g_strdup(g_hash_table_lookup(t, key));
  g_hash_table_unref(t);
  return v;
}

/* Returns TRUE if the app dealt with this address itself (so the web view
   should not go there). */
static gboolean handle_uri(const char *uri) {
  GUri *u = g_uri_parse(uri, G_URI_FLAGS_ENCODED, NULL);
  if (!u) return FALSE;
  const char *scheme = g_uri_get_scheme(u), *host = g_uri_get_host(u), *path = g_uri_get_path(u);
  gboolean handled = FALSE;
  if (g_strcmp0(scheme, "about") == 0 || g_strcmp0(scheme, "data") == 0 || g_strcmp0(scheme, "blob") == 0) {
    handled = FALSE;
  } else if (g_strcmp0(host, "hehs-live.duckdns.org") == 0 && path && g_str_has_prefix(path, "/auth/v1/authorize")) {
    char *provider = query_param(u, "provider");
    if (!provider || g_strcmp0(provider, "google") == 0) {
      open_outside(HOME_URL "?applogin=google&ret=win");
      handled = TRUE;
    } else {
      signing_in = TRUE;
    }
    g_free(provider);
  } else if (g_strcmp0(scheme, "https") != 0 && g_strcmp0(scheme, "http") != 0) {
    open_outside(uri);
    handled = TRUE;
  } else if (is_ours(host)) {
    if (host && g_str_has_suffix(host, "civilinitiatives.org")) signing_in = FALSE;
  } else if (!signing_in) {
    open_outside(uri);
    handled = TRUE;
  }
  g_uri_unref(u);
  return handled;
}

static gboolean on_decide(WebKitWebView *v, WebKitPolicyDecision *d, WebKitPolicyDecisionType type, gpointer data) {
  if (type == WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION || type == WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) {
    WebKitNavigationAction *a = webkit_navigation_policy_decision_get_navigation_action(WEBKIT_NAVIGATION_POLICY_DECISION(d));
    const char *uri = webkit_uri_request_get_uri(webkit_navigation_action_get_request(a));
    if (handle_uri(uri)) { webkit_policy_decision_ignore(d); return TRUE; }
    if (type == WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) {
      /* target="_blank" on our own pages: open here */
      webkit_web_view_load_uri(v, uri);
      webkit_policy_decision_ignore(d);
      return TRUE;
    }
  }
  return FALSE;
}

static const char *OFFLINE =
  "<html><body style='margin:0;height:100vh;display:grid;place-items:center;background:#3730A3;color:#fff;"
  "font-family:sans-serif;text-align:center'><div><div style='font-size:64px'>🚀</div><h2>Can't reach the Launchpad</h2>"
  "<p style='color:#c7d2fe'>Check the internet connection, then try again.</p>"
  "<a href='https://civilinitiatives.org/' style='display:inline-block;padding:12px 22px;border-radius:12px;background:#f59e0b;"
  "color:#fff;font-weight:700;text-decoration:none'>Try again</a></div></body></html>";

static gboolean on_fail(WebKitWebView *v, WebKitLoadEvent ev, char *uri, GError *err, gpointer data) {
  if (g_error_matches(err, WEBKIT_NETWORK_ERROR, WEBKIT_NETWORK_ERROR_CANCELLED)) return FALSE;
  if (g_error_matches(err, WEBKIT_POLICY_ERROR, WEBKIT_POLICY_ERROR_FRAME_LOAD_INTERRUPTED_BY_POLICY_CHANGE)) return FALSE;
  webkit_web_view_load_alternate_html(v, OFFLINE, uri, NULL);
  return TRUE;
}

static void on_title(WebKitWebView *v, GParamSpec *p, gpointer data) {
  const char *t = webkit_web_view_get_title(v);
  if (!t || !*t || g_strcmp0(t, "Homework Launchpad") == 0) gtk_window_set_title(GTK_WINDOW(window), "Homework Launchpad");
  else { char *s = g_strdup_printf("%s - Homework Launchpad", t); gtk_window_set_title(GTK_WINDOW(window), s); g_free(s); }
}

static gboolean on_key(GtkWidget *w, GdkEventKey *e, gpointer data) {
  if (e->keyval == GDK_KEY_F11) {
    if (fullscreen) gtk_window_unfullscreen(GTK_WINDOW(window)); else gtk_window_fullscreen(GTK_WINDOW(window));
    fullscreen = !fullscreen;
    return TRUE;
  }
  return FALSE;
}

/* hehlaunchpad://login#at=..&rt=.. */
static gboolean take_session(const char *link) {
  if (!link || !g_str_has_prefix(link, "hehlaunchpad:")) return FALSE;
  const char *hash = strchr(link, '#');
  if (!hash) return FALSE;
  GHashTable *t = g_uri_parse_params(hash + 1, -1, "&", G_URI_PARAMS_NONE, NULL);
  if (!t) return FALSE;
  const char *at = g_hash_table_lookup(t, "at"), *rt = g_hash_table_lookup(t, "rt");
  gboolean ok = FALSE;
  if (at && rt && *at && *rt && web) {
    char *ea = g_uri_escape_string(at, NULL, FALSE), *er = g_uri_escape_string(rt, NULL, FALSE);
    char *url = g_strdup_printf(HOME_URL "?appsession=1#at=%s&rt=%s", ea, er);
    webkit_web_view_load_uri(web, url);
    g_free(url); g_free(ea); g_free(er);
    ok = TRUE;
  }
  g_hash_table_unref(t);
  return ok;
}

static void build_window(GtkApplication *app) {
  if (window) return;
  char *base = g_build_filename(g_get_user_data_dir(), "homework-launchpad", NULL);
  char *cache = g_build_filename(g_get_user_cache_dir(), "homework-launchpad", NULL);
  WebKitWebsiteDataManager *dm = webkit_website_data_manager_new("base-data-directory", base, "base-cache-directory", cache, NULL);
  char *cookies = g_build_filename(base, "cookies.sqlite", NULL);
  g_mkdir_with_parents(base, 0700);
  webkit_cookie_manager_set_persistent_storage(webkit_website_data_manager_get_cookie_manager(dm), cookies, WEBKIT_COOKIE_PERSISTENT_STORAGE_SQLITE);
  WebKitWebContext *ctx = webkit_web_context_new_with_website_data_manager(dm);

  WebKitUserContentManager *ucm = webkit_user_content_manager_new();
  webkit_user_content_manager_add_script(ucm, webkit_user_script_new(
    "window.LaunchpadApp={platform:function(){return 'linux'},version:function(){return '2.0'},"
    "openGame:function(n){ if(n==='rush') location.href='https://arcade.civilinitiatives.org/rush.html?q=mid'; }};",
    WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES, WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, NULL, NULL));

  web = WEBKIT_WEB_VIEW(g_object_new(WEBKIT_TYPE_WEB_VIEW, "web-context", ctx, "user-content-manager", ucm, NULL));
  WebKitSettings *s = webkit_web_view_get_settings(web);
  webkit_settings_set_user_agent_with_application_details(s, "LaunchpadApp", "2.0");
  webkit_settings_set_enable_developer_extras(s, FALSE);
  webkit_settings_set_javascript_can_open_windows_automatically(s, TRUE);
  webkit_settings_set_enable_smooth_scrolling(s, TRUE);
  webkit_settings_set_hardware_acceleration_policy(s, WEBKIT_HARDWARE_ACCELERATION_POLICY_ALWAYS);

  window = gtk_application_window_new(app);
  gtk_window_set_title(GTK_WINDOW(window), "Homework Launchpad");
  gtk_window_set_icon_name(GTK_WINDOW(window), "org.civilinitiatives.launchpad");
  gtk_window_set_default_size(GTK_WINDOW(window), 1280, 860);
  gtk_container_add(GTK_CONTAINER(window), GTK_WIDGET(web));
  g_signal_connect(web, "decide-policy", G_CALLBACK(on_decide), NULL);
  g_signal_connect(web, "load-failed", G_CALLBACK(on_fail), NULL);
  g_signal_connect(web, "notify::title", G_CALLBACK(on_title), NULL);
  g_signal_connect(window, "key-press-event", G_CALLBACK(on_key), NULL);
  gtk_widget_show_all(window);
  webkit_web_view_load_uri(web, HOME_URL);
  g_free(base); g_free(cache); g_free(cookies);
}

static void on_activate(GtkApplication *app, gpointer data) {
  build_window(app);
  gtk_window_present(GTK_WINDOW(window));
}

static void on_open(GApplication *app, GFile **files, gint n, const char *hint, gpointer data) {
  build_window(GTK_APPLICATION(app));
  for (int i = 0; i < n; i++) {
    char *uri = g_file_get_uri(files[i]);
    take_session(uri);
    g_free(uri);
  }
  gtk_window_present(GTK_WINDOW(window));
}

int main(int argc, char **argv) {
  g_set_prgname("homework-launchpad");
  GtkApplication *app = gtk_application_new("org.civilinitiatives.launchpad", G_APPLICATION_HANDLES_OPEN);
  g_signal_connect(app, "activate", G_CALLBACK(on_activate), NULL);
  g_signal_connect(app, "open", G_CALLBACK(on_open), NULL);
  int r = g_application_run(G_APPLICATION(app), argc, argv);
  g_object_unref(app);
  return r;
}
