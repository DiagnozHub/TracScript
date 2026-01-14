# TracScript

TracScript is an Android platform for collecting, processing, and transmitting telemetry from mobile devices and external sources (GPS, accelerometer, system events), with support for plugins and multiple tracking protocols.
The project is designed for both industrial and private use cases, including transportation, telematics, diagnostics, automation, and integration with monitoring systems such as Traccar <https://github.com/traccar/traccar>, Wialon.

## Key Features
- GPS data collection (Location API, NMEA)
- Motion detection based on accelerometer data
- Coordinate filtering and normalization (warmup, anchor logic)
- Plugin-based architecture (enable/disable modules without rebuilding)
- Support for multiple tracking protocols
- Extended debug logging with UI log viewer

## Supported Navigation Data Protocols
- OsmAnd (Traccar-compatible)
- Wialon IPS

## Plugins
All application functionality is implemented via plugins.
Currently implemented plugins include:
- GPS collection, processing, and transmission of navigation coordinates to telematics servers.
- Scenarios - allows controlling an Android smartphone: launching applications, searching for text, pressing buttons, and more.
The main idea of the Scenarios plugin is to collect required information from the smartphone and transmit it to navigation servers together with GPS coordinates.

## Screenshots
<p align="center">
  <img src="https://github.com/user-attachments/assets/87167e87-c022-41c0-b842-00ea80043974" width="250">
  <img src="https://github.com/user-attachments/assets/2cbd6d35-3fb5-422f-88be-37e8a37a5801" width="250">
  <img src="https://github.com/user-attachments/assets/5267de6a-fb6f-4cf8-8b75-e610db376b62" width="250">
</p>

## Author
Contact: diagnoz174@gmail.com
