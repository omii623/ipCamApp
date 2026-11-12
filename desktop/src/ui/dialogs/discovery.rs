//! Kamerakeresés: ONVIF (WS-Discovery) + port-scan párhuzamosan, majd ONVIF-találatnál
//! belépés és stream-választás. A kiválasztott találat a kamera-szerkesztőben nyílik meg.

use super::{Editor, Task, form_row};
use crate::i18n::{Lang, lang, t};
use crate::model::{Camera, StreamType};
use crate::net::onvif::{self, OnvifDevice, OnvifError, OnvifStream};
use crate::net::scanner;
use crate::storage::Settings;
use crate::ui::IpCamApp;
use crate::ui::theme::ERROR;
use eframe::egui::{self, RichText};
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

const ONVIF_TIMEOUT: Duration = Duration::from_secs(4);

// ---- találatok ----

#[derive(Clone)]
enum FoundKind {
    Onvif(OnvifDevice),
    Port(u16),
}

#[derive(Clone)]
struct Found {
    host: String,
    kind: FoundKind,
}

impl Found {
    /// (jelvény, cím, alcím) a listához.
    fn labels(&self) -> (&'static str, String, String) {
        let open_port = |p: u16| format!("{} {p}", t("Nyitott port:", "Open port:"));
        match &self.kind {
            FoundKind::Onvif(dev) => ("ONVIF", dev.name.clone(), format!("{}  {}", self.host, dev.hardware)),
            FoundKind::Port(p) if scanner::RTSP_PORTS.contains(p) => {
                ("RTSP", self.host.clone(), open_port(*p))
            }
            FoundKind::Port(p) => ("HTTP", self.host.clone(), open_port(*p)),
        }
    }

    /// Rendezési kulcs: IP cím szerint.
    fn sort_key(&self) -> u32 {
        self.host.parse::<Ipv4Addr>().map(u32::from).unwrap_or(u32::MAX)
    }
}

/// A háttérszálak által töltött közös állapot.
#[derive(Default)]
struct Progress {
    found: Vec<Found>,
    onvif_running: bool,
    scan_running: bool,
    scanned: usize,
    total: usize,
    error: Option<String>,
}

// ---- keresés ----

pub struct Discovery {
    range: String,
    ports: String,
    own_ip: Option<Ipv4Addr>,
    progress: Arc<Mutex<Progress>>,
    cancel: Arc<AtomicBool>,
    login: Option<OnvifLogin>,
}

impl Discovery {
    /// Megnyitás a saját hálózat tartományával, és a keresés azonnali indítása.
    pub fn new(settings: &Settings, ctx: &egui::Context) -> Self {
        let own_ip = scanner::local_ipv4();
        let mut d = Discovery {
            range: own_ip.map(scanner::default_range).unwrap_or_default(),
            ports: settings.search_ports.clone(),
            own_ip,
            progress: Arc::default(),
            cancel: Arc::default(),
            login: None,
        };
        d.start(ctx);
        d
    }

    fn running(&self) -> bool {
        let p = self.progress.lock().unwrap();
        p.onvif_running || p.scan_running
    }

    fn stop(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }

    /// Új keresés: a futó leáll, az eredmények törlődnek.
    fn start(&mut self, ctx: &egui::Context) {
        self.stop();
        self.cancel = Arc::default();
        self.progress = Arc::default();
        self.start_onvif(ctx);
        match (scanner::parse_range(&self.range), scanner::parse_ports(&self.ports)) {
            (Ok(ips), Ok(ports)) => self.start_scan(ctx, ips, ports),
            (Err(e), _) | (_, Err(e)) => self.progress.lock().unwrap().error = Some(e.to_string()),
        }
    }

    fn start_onvif(&self, ctx: &egui::Context) {
        self.progress.lock().unwrap().onvif_running = true;
        let (progress, cancel, ctx) = (self.progress.clone(), self.cancel.clone(), ctx.clone());
        std::thread::spawn(move || {
            let result = onvif::discover(
                ONVIF_TIMEOUT,
                || cancel.load(Ordering::Relaxed),
                |dev| {
                    let found = Found { host: dev.host.clone(), kind: FoundKind::Onvif(dev) };
                    progress.lock().unwrap().found.push(found);
                    ctx.request_repaint();
                },
            );
            let mut p = progress.lock().unwrap();
            p.onvif_running = false;
            if let Err(e) = result {
                p.error.get_or_insert(format!("ONVIF: {e}"));
            }
            ctx.request_repaint();
        });
    }

    fn start_scan(&self, ctx: &egui::Context, ips: Vec<Ipv4Addr>, ports: Vec<u16>) {
        {
            let mut p = self.progress.lock().unwrap();
            p.scan_running = true;
            p.total = ips.len();
        }
        let (progress, cancel, ctx) = (self.progress.clone(), self.cancel.clone(), ctx.clone());
        std::thread::spawn(move || {
            let (p1, p2, c1, c2) = (progress.clone(), progress.clone(), ctx.clone(), ctx.clone());
            let cancel2 = cancel.clone();
            scanner::scan(
                ips,
                ports,
                move |ip, port| {
                    p1.lock()
                        .unwrap()
                        .found
                        .push(Found { host: ip.to_string(), kind: FoundKind::Port(port) });
                    c1.request_repaint();
                },
                move |done| {
                    p2.lock().unwrap().scanned = done;
                    c2.request_repaint();
                },
                move || cancel2.load(Ordering::Relaxed),
            );
            progress.lock().unwrap().scan_running = false;
            ctx.request_repaint();
        });
    }

    // ---- felület ----

    fn search_form(&mut self, ui: &mut egui::Ui) {
        egui::Grid::new("disc_grid").num_columns(2).spacing([10.0, 6.0]).show(ui, |ui| {
            form_row(ui, t("IP tartomány", "IP range"), |ui| {
                ui.add(
                    egui::TextEdit::singleline(&mut self.range)
                        .hint_text("192.168.0.1-254, 10.0.0.0/24")
                        .desired_width(260.0),
                );
                if let Some(ip) = self.own_ip
                    && ui
                        .small_button("🏠")
                        .on_hover_text(format!("{} ({ip})", t("Saját hálózat", "Own network")))
                        .clicked()
                {
                    self.range = scanner::default_range(ip);
                }
            });
            form_row(ui, t("Portok", "Ports"), |ui| {
                ui.add(
                    egui::TextEdit::singleline(&mut self.ports).hint_text("554, 8554").desired_width(260.0),
                );
            });
        });
        if self.running() {
            if ui.button(format!("⏹ {}", t("Leállítás", "Stop"))).clicked() {
                self.stop();
            }
        } else if ui.button(format!("🔍 {}", t("Keresés", "Search"))).clicked() {
            self.start(ui.ctx());
        }
    }

    fn status(&self, ui: &mut egui::Ui) {
        let p = self.progress.lock().unwrap();
        ui.add_space(4.0);
        if p.scan_running && p.total > 0 {
            let bar = egui::ProgressBar::new(p.scanned as f32 / p.total as f32)
                .text(format!("{}/{}", p.scanned, p.total));
            ui.add(bar);
        }
        let text = if p.onvif_running {
            t("ONVIF kamerák keresése…", "Searching for ONVIF cameras…").to_string()
        } else if p.scan_running {
            t("Portok keresése…", "Scanning ports…").to_string()
        } else if p.found.is_empty() {
            t(
                "Nem található kamera. Ellenőrizd, hogy a gép ugyanazon a hálózaton van-e.",
                "No cameras found. Make sure this computer is on the same network.",
            )
            .to_string()
        } else {
            match lang() {
                Lang::Hu => format!("Kész – {} találat. Kattints egyre a hozzáadáshoz.", p.found.len()),
                Lang::En => format!("Done – {} found. Click one to add it.", p.found.len()),
            }
        };
        ui.horizontal(|ui| {
            if p.onvif_running || p.scan_running {
                ui.spinner();
            }
            ui.label(text);
        });
        if let Some(e) = &p.error {
            ui.colored_label(ERROR, e);
        }
    }

    /// A találatok IP szerint rendezve; visszaadja a kattintottat.
    fn results(&self, ui: &mut egui::Ui) -> Option<Found> {
        let mut list = self.progress.lock().unwrap().found.clone();
        list.sort_by_key(Found::sort_key);
        let mut picked = None;
        egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
            for found in list {
                let (badge, title, sub) = found.labels();
                let button = egui::Button::new(format!("{title}\n{sub}"))
                    .right_text(RichText::new(badge).small().strong())
                    .min_size(egui::vec2(ui.available_width(), 0.0));
                if ui.add(button).on_hover_text(t("Hozzáadás", "Add")).clicked() {
                    picked = Some(found);
                }
            }
        });
        picked
    }
}

impl Drop for Discovery {
    fn drop(&mut self) {
        self.stop();
    }
}

// ---- ONVIF belépés + stream-választás ----

struct OnvifLogin {
    device: OnvifDevice,
    user: String,
    pass: String,
    query: Option<Task<Result<Vec<OnvifStream>, String>>>,
}

impl OnvifLogin {
    fn new(device: OnvifDevice, last_login: Option<&(String, String)>) -> Self {
        let (user, pass) = last_login.cloned().unwrap_or_default();
        OnvifLogin { device, user, pass, query: None }
    }

    fn start_query(&mut self, ctx: &egui::Context) {
        let (dev, user, pass) = (self.device.clone(), self.user.clone(), self.pass.clone());
        self.query = Some(Task::spawn(ctx, move || match onvif::get_streams(&dev, &user, &pass) {
            Ok(v) if v.is_empty() => {
                Err(t("A kamera nem adott vissza stream címet.", "The camera returned no stream address.")
                    .into())
            }
            Ok(v) => Ok(v),
            Err(OnvifError::Auth) => {
                Err(t("Hibás felhasználónév vagy jelszó.", "Wrong username or password.").into())
            }
            Err(OnvifError::Other(e)) => Err(e),
        }));
    }

    /// Kézi kitöltés ONVIF-lekérdezés nélkül (RTSP alapértékekkel).
    fn manual_camera(&self) -> Camera {
        let mut c = Camera::new(&self.device.name, &self.device.host, 554, StreamType::Rtsp, "/stream1");
        c.username = self.user.clone();
        c.password = self.pass.clone();
        c
    }

    /// Az ablak; visszaadja a kiválasztott (előre kitöltött) kamerát, és hogy nyitva maradt-e.
    fn window(&mut self, ctx: &egui::Context) -> (Option<Camera>, bool) {
        let mut open = true;
        let mut chosen = None;
        egui::Window::new(self.device.name.clone())
            .id(egui::Id::unique("onvif_login"))
            .open(&mut open)
            .collapsible(false)
            .resizable(false)
            .order(egui::Order::Foreground)
            .show(ctx, |ui| {
                ui.label(t(
                    "Add meg a kamera belépési adatait, hogy lekérdezzük a pontos stream címet.",
                    "Enter the camera's login to query the exact stream address.",
                ));
                egui::Grid::new("login_grid").num_columns(2).show(ui, |ui| {
                    form_row(ui, t("Felhasználónév", "Username"), |ui| {
                        ui.text_edit_singleline(&mut self.user);
                    });
                    form_row(ui, t("Jelszó", "Password"), |ui| {
                        ui.add(egui::TextEdit::singleline(&mut self.pass).password(true));
                    });
                });
                let running = self.query.as_ref().is_some_and(Task::is_running);
                ui.horizontal(|ui| {
                    if ui.add_enabled(!running, egui::Button::new(t("Csatlakozás", "Connect"))).clicked() {
                        self.start_query(ui.ctx());
                    }
                    let manual_hint =
                        t("Kitöltés kézzel, ONVIF lekérdezés nélkül", "Fill in manually without ONVIF query");
                    if ui.button(t("Kézzel", "Manually")).on_hover_text(manual_hint).clicked() {
                        chosen = Some(self.manual_camera());
                    }
                    if running {
                        ui.spinner();
                    }
                });
                if let Some(task) = &self.query {
                    match task.result().as_ref() {
                        Some(Err(e)) => {
                            ui.colored_label(ERROR, e);
                        }
                        Some(Ok(streams)) => {
                            ui.separator();
                            ui.label(t("Válassz streamet:", "Choose a stream:"));
                            for s in streams {
                                if ui.button(format!("{}\n{}", s.profile_name, s.uri)).clicked() {
                                    chosen = Some(onvif::to_camera(
                                        &self.device.name,
                                        &s.uri,
                                        &self.user,
                                        &self.pass,
                                    ));
                                }
                            }
                        }
                        None => {}
                    }
                }
            });
        (chosen, open)
    }
}

// ---- az ablak ----

pub(super) fn discovery_window(app: &mut IpCamApp, ctx: &egui::Context) {
    let Some(d) = app.discovery.as_mut() else {
        return;
    };
    let mut open = true;
    let mut picked = None;
    egui::Window::new(t("Kamera keresés", "Find cameras"))
        .id(egui::Id::unique("discovery"))
        .open(&mut open)
        .collapsible(false)
        .default_size([520.0, 480.0])
        .show(ctx, |ui| {
            d.search_form(ui);
            d.status(ui);
            ui.separator();
            picked = d.results(ui);
        });

    match picked.map(|f| (f.host, f.kind)) {
        Some((_, FoundKind::Onvif(dev))) => d.login = Some(OnvifLogin::new(dev, app.last_login.as_ref())),
        Some((host, FoundKind::Port(port))) => {
            let kind = if scanner::RTSP_PORTS.contains(&port) { StreamType::Rtsp } else { StreamType::Mjpeg };
            let name = format!("{} {host}", t("Kamera", "Camera"));
            app.editor = Some(Editor::new(Camera::new(&name, &host, port, kind, kind.default_path()), true));
        }
        None => {}
    }

    if let Some(login) = d.login.as_mut() {
        let (chosen, still_open) = login.window(ctx);
        if let Some(cam) = chosen {
            app.editor = Some(Editor::new(cam, true));
            d.login = None;
        } else if !still_open {
            d.login = None;
        }
    }
    if !open {
        app.discovery = None;
    }
}
