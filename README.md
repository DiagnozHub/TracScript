# TracScript

TracScript is an Android platform for collecting, processing, and transmitting telemetry from mobile devices and external sources (GPS, accelerometer, system events), with support for plugins and multiple tracking protocols.
The project is designed for both industrial and private use cases, including transportation, telematics, diagnostics, automation, and integration with monitoring systems such as Traccar <https://github.com/traccar/traccar>, Wialon.

## Key Features
- GPS data collection (Location API, NMEA)
- Motion detection based on accelerometer data (MotionDetector)
- Coordinate filtering and normalization (warmup, anchor logic)
- Local telemetry storage (SQLite)
- Reliable data transmission with retry logic
- Plugin-based architecture (enable/disable modules without rebuilding)
- Support for multiple tracking protocols
- Extended debug logging with UI log viewer
- Background operation (Foreground Service, auto-start)

## Supported Navigation Data Protocols
- Wialon IPS
- OsmAnd (Traccar-compatible)

## Plugins
All application functionality is implemented via plugins.
Currently implemented plugins include:
- GPS collection, processing, and transmission of navigation coordinates to telematics servers.
- Scenarios - allows controlling an Android smartphone: launching applications, searching for text, pressing buttons, and more.
The main idea of the Scenarios plugin is to collect required information from the smartphone and transmit it to navigation servers together with GPS coordinates.

## Author
Development and architecture — Artem Nagumanov
Contact: nagumanov174@gmail.com
