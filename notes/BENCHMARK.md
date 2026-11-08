# SiliconShaders Render-Diagnose auf Apple Silicon

Im SiliconShaders-Grafikmenü unten **Benchmark → Komplette Render-Diagnose starten** wählen. Der Menüstand wird gespeichert, die aktuelle Welt verlassen und die separate Welt `siliconshaders-benchmark` mit festem Seed geladen oder angelegt. Die Diagnose umfasst 97 Messfälle und dauert ungefähr 20 Minuten zuzüglich Laden und Aufwärmen. Der Kurztest dauert etwa zwei Minuten. Esc bricht ab und speichert die bisher abgeschlossenen Fälle.

Der Menüstart verwendet **1920 × 1200 physische Pixel im exklusiven Vollbild**, VSync aus und unbegrenzte FPS. Es gibt keine Menü-, AFK- oder Hintergrund-FPS-Begrenzung während des Tests. Der höchste angebotene Bildwiederholmodus dieser Auflösung wird gewählt. Die tatsächliche Auflösung, der Modus und das effektive FPS-Limit werden geprüft und pro Messung dokumentiert. Bietet der Monitor die genaue Auflösung nicht an oder aktiviert das Spiel einen anderen Modus, endet der Test mit einer Fehlermeldung. Es wird keine ähnliche Auflösung gewählt.

Die Mod unterstützt Apple-Silicon-Macs mit ihrer bisherigen Laufzeitumgebung: macOS ab 15, Minecraft 26.3, Fabric und ARM64-Java 25. Es gibt keine Beschränkung auf bestimmte M-Generationen oder Pro/Max/Ultra. Die Hardware-Erkennung und vorhandene GPU-Pfadwahl bleiben erhalten; Metal 4 oder Raytracing werden für diesen Test nicht benötigt. Ein vollständiger Minecraft-Client wird benötigt.

## Was geprüft wird

- Standard-Shader, Shader aus und eigene Einstellungen; Schattenauflösung, Kaskaden, Weite, Filter, Culling und Caches.
- MetalFX mit 85/67/50 Prozent Renderauflösung, SSAO, Wasser-Spiegelung und Brechung, Unterwasser sowie Material-Reflexionen.
- Bloom Original/HDR, Tiefenschärfe, Bewegungsunschärfe, Lens Flare, Farbfehler, Vignette, Schärfen und beide Godray-Varianten; zusätzlich Kombinationen mit HDR und MetalFX.
- Tonkurven, automatische Belichtung, Nacht, Polarlicht, eigene Mondphasen, Regen, nasse Oberflächen und Spritzer.
- Eine wiederholbare Szene mit 64 Rüstungsständern, verschiedenen Materialien und Lichtquellen; farbiges Blocklicht mit verschiedenen Einstellungen.
- Eine abschließende Standard-Messung derselben Landschaft zur Erkennung zeitlicher Drift.

Die Szene dreht die Kamera einmal um 360 Grad. Chunks müssen sich vorher stabilisieren; anschließend wird mindestens drei Sekunden aufgewärmt und acht Sekunden gemessen. Nässe wird pro Szene fest vorgegeben: Regen vollständig nass, sonst trocken. Das vermeidet Unterschiede aufgrund der Reihenfolge vorheriger Regenszenen. Die Testwelt darf nicht manuell verändert werden; bei verändertem Welt-Seed wird abgebrochen.

## Ergebnisse und Finder

Nach Abschluss, Abbruch oder einem erfassten Fehler öffnet sich **automatisch der Finder mit der `.log`-Datei ausgewählt**. Die Dateien liegen im Minecraft-Spielordner unter `siliconshaders-benchmarks/`:

- `.log`: Ablauf, Ergebnisübersicht, Einstellungen und Messdetails einschließlich GPU-Pässen sowie bis zu 2 MiB aus `logs/latest.log`.
- `.json`: vollständige Daten und rohe Framezeiten zum späteren Auswerten.
- `.txt`: lesbare Übersicht und Diagnosehinweise.
- `.html`: Offline-Bericht. Über die Dateiauswahl mehrere JSON-Dateien anderer Macs laden und nebeneinander vergleichen. Es werden keine Dateien hochgeladen und keine externen Skripte geladen.

Im Menü und auf der Ergebnisseite kann der Ergebnisordner erneut geöffnet werden. Die Ergebnisseite bietet auch einen Knopf für den HTML-Bericht. Bei einem Prozessabsturz bleiben `.json.inprogress`, `.txt.inprogress` und der Ablauf-Log erhalten; `currentCase` nennt den begonnenen Fall. Die `.json.inprogress` ist gültiges JSON und kann für die Auswertung kopiert und in `.json` umbenannt werden. Ein harter Prozess-/GPU-Absturz kann kein Finder-Fenster mehr öffnen.

Die Messungen enthalten Durchschnitts-FPS, 1%-Low, p50/p95/p99 und maximale Framezeit, GPU-Command-Buffer-Zeit, Renderthread-CPU-Zeit, GPU-Wartezeit, GC/JIT-Aktivität, Speicher, Chip/GPU-Kerne, Stromquelle, Stromsparmodus und Temperaturzustand. Die Temperatur ist eine macOS-Zustandsstufe, keine Grad-Celsius-Messung. MetalFX- und HDR-Ziel-Zähler helfen, angeforderte Effekte von einem Fallback zu unterscheiden. GPU-Pass-Spannen stammen aus Stichproben alle 30 Submits; überlappende Spannen dürfen nicht addiert werden. Letzte noch laufende Command Buffer fehlen in der GPU-Zeit-Schätzung.

„Δ Standard“ bezeichnet die Änderung der mittleren Framezeit gegenüber der Standard-Variante derselben Szene. Ein großer Anstieg ist ein Profiling-Kandidat, kein automatischer Nachweis eines Bugs. Ein kompletter Funktions-/Bildtest, Server-/Weltgenerierungs-Benchmark oder Nachweis der P-/E-Core-Belegung ist dies nicht. Zusätzliche Startup-Pfade wie optionales LOD werden im vorhandenen Zustand protokolliert, nicht automatisch über Neustarts durchgetestet. Bildfehler müssen zusätzlich im Spiel geprüft werden.

## Macs vergleichen

Auf allen Macs dieselbe JAR nutzen. Möglichst am Netzteil testen, Stromsparmodus ausschalten und Hintergrundprogramme schließen. Nach vergleichbarer Abkühlzeit beginnen. HTML kennzeichnet abweichende Versionen, Szenenorte, Einstellungen und Auflösungen; weitere Unterschiede wie JVM, Mods und Temperatur sind im Bericht sichtbar. Die Mod-Menüwerte und vorigen Spiel-/Anzeigeoptionen werden nach Ende oder Abbruch wiederhergestellt.

Für Entwickler kann eine einmalige Datei `config/siliconshaders-benchmark.json` den Lauf starten:

```json
{"profile":"diagnose","window":"fullscreen","mode":"1920x1200","quit":false}
```

`seconds`, `scenes` und `variants` können den Lauf für einen gezielten Nachtest verkürzen. Die Datei wird beim Start verbraucht. Ältere Profile `kurz`, `standard` und `voll` bleiben verfügbar; die vollständige aktuelle Render-Diagnose heißt `diagnose`. Abweichende Fenster-/Auflösungsoptionen sind für Entwickler weiter möglich und werden im Bericht ausgewiesen.

Prüfungen: `./gradlew :metal:build :metal:graphicsMenuChecks :metal:hdrPipelineChecks --offline` mit Java 25; Report-Import/Abgleich zusätzlich mit `node metal/src/test/js/bench-report-checks.mjs`.
