# dal-avdevices-power-middleatlantic-select
DAL adapter for MiddleAtlantic Select devices

Monitors and controls Middle Atlantic Select Series power units over the device's TCP control protocol.
Data is collected in the background at the monitoring rate; outlet states are kept as the last known values
when the TCP protocol is occupied by another client.

## Requirements
- Java 17
- Symphony with Spring 7 (Spring is provided by the Symphony runtime)

## Configuration
| Property | Description |
|---|---|
| Host | IP address or hostname of the power unit |
| Port | TCP control port of the device (e.g. 60000) |
| Login | Device username |
| Password | Device password |

## Monitored properties
| Property | Description | Values |
|---|---|---|
| ControlProtocolStatus | Whether the device's TCP control protocol could be used in the last cycle | AVAILABLE, UNAVAILABLE |
| Outlets#Outlet\<n\> | State of outlet number *n*. Controllable outlets are a switch control, non-controllable outlets are read-only | Controllable: 0 (off), 1 (on). Non-controllable: Off, On |
| Outlets#Outlet\<n\>Name | Outlet name configured on the device. Omitted when the device returns no name | Text |
| AdapterMetadata#AdapterVersion | Adapter version | e.g. 1.0.7 |
| AdapterMetadata#AdapterBuildDate | Adapter build date | yyyy-MM-dd HH:mm:ss |
| AdapterMetadata#AdapterUptime | Time since the adapter started | e.g. 2 d 3 hr 5 min 38 sec |
| AdapterMetadata#AdapterUptime(min) | Time since the adapter started, in minutes | Number |

Outlets the device reports as not present are not listed.

## Controls
| Property | Type | Description |
|---|---|---|
| Outlets#Outlet\<n\> | Switch | Turns a controllable outlet on (1) or off (0) |
| Sequence up | Button | Runs the device power-on sequence for all controllable outlets. Shown when at least one controllable outlet is off |
| Sequence down | Button | Runs the device power-off sequence for all controllable outlets. Shown when at least one controllable outlet is on |

### Property names before 1.0.7
| Before | From 1.0.7 |
|---|---|
| Controllable outlets#\<deviceName\> - \<n\> | Outlets#Outlet\<n\> + Outlets#Outlet\<n\>Name |
| \<deviceName\> - \<n\> (non-controllable, ungrouped) | Outlets#Outlet\<n\> + Outlets#Outlet\<n\>Name |

## Build
**Build project with no tests: ``` mvn clean install -DskipTests```**

**Build project with unit tests: ``` mvn clean install ```**

**Run integration tests against a real device: ``` mvn test -DexcludedGroups= -Dgroups=integrationTest ```**
(set the device address and credentials in the `MIDDLEATLANTIC_HOST`, `MIDDLEATLANTIC_PORT`, `MIDDLEATLANTIC_LOGIN`
and `MIDDLEATLANTIC_PASSWORD` environment variables; never commit them)
