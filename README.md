# SafePathApp

SafePathApp is an Android application designed to help users find safe and optimal routes for various modes of transport (driving, walking, cycling) by considering not just distance and duration, but also safety factors. The app leverages OpenStreetMap data and routing services to provide intelligent route suggestions. The was my final year project in college and a research paper on the project "Real-time smart path recommendation for peoples' safety" was presented and published in the 4th IEEE conference - ICKECS 2026 (https://heyzine.com/flip-book/5ed039d141.html) 

## Features

*   **Location-Based Routing:** Get routes from your current location to a searched destination.
*   **Multiple Transport Modes:** Choose between driving, walking, and cycling routes.
*   **Enhanced Safety Scoring:** Routes are evaluated based on a custom safety algorithm that considers factors like time of day, estimated traffic density, and perceived road types.
*   **Alternative Routes:** The app attempts to provide multiple route options (safest, fastest, balanced).
*   **Interactive Map:** Utilizes `osmdroid` for an interactive map experience with markers and polylines for routes.
*   **User Location Tracking:** Displays the user's current location on the map.
*   **Search Functionality:** Easily search for locations using text input.
*   **Dynamic Route Info Card:** Displays detailed information about the selected route, including distance, duration, safety score, and contributing safety factors.
*   **Route Legend:** Explains the color-coding for different route types (safest, fastest, balanced).

## How it Works

The application integrates with OpenStreetMap (via Nominatim for geocoding) and uses routing services like OpenRouteService (ORS) and OSRM (Open Source Routing Machine) to calculate routes.

1.  **Search Location:** Users enter a destination, which is then geocoded to obtain its geographical coordinates.
2.  **Choose Transport Mode:** Users select their preferred mode of transport (driving, walking, cycling).
3.  **Route Calculation:** The app attempts to fetch routes using OpenRouteService. If ORS fails or returns no suitable routes, it falls back to OSRM. For walking and driving, it tries to generate multiple alternative routes using OSRM's `alternatives` parameter or by introducing artificial waypoints.
4.  **Safety Scoring:** Each fetched route undergoes a custom safety analysis, which considers:
    *   **Time of Day:** Different safety implications for daylight, dawn/dusk, and night hours.
    *   **Route Density:** A simulated measure to infer whether the route passes through urban/high-traffic or quiet/highway areas, impacting safety for different transport modes.
    *   **Distance and Duration:** Longer distances or significantly slow/fast average speeds can influence the score.
5.  **Route Display:** The routes are displayed on the map using `osmdroid` polylines, with different colors and line styles to indicate safety and route type. The safest route is highlighted.
6.  **Information Display:** A dynamic card shows detailed metrics for the primary (safest) route, including an overall safety score and specific factors that contributed to it.

## Setup Instructions

To set up and run SafePathApp on your local machine, follow these steps:

1.  **Clone the Repository (if applicable):**
    ```bash
    git clone [repository_url]
    cd SafePathApp
    ```

2.  **Open in Android Studio:**
    *   Open Android Studio.
    *   Select "Open an existing Android Studio project."
    *   Navigate to the root directory of the cloned project (`SafePathApp/SafePathApp`) and click "OK."

3.  **Gradle Sync:**
    *   Android Studio should automatically perform a Gradle sync. If not, click "Sync Project with Gradle Files" (the elephant icon in the toolbar).

4.  **OpenRouteService API Key:**
    *   The app uses OpenRouteService for routing. You need an API key for full functionality.
    *   Currently, a placeholder key is embedded in `MapActivity.kt`. For production use, it's recommended to:
        *   Obtain your own free API key from [OpenRouteService](https://openrouteservice.org/sign-up/).
        *   Store your API key securely (e.g., in `local.properties` or as a build secret) and access it programmatically in `MapActivity.kt`.
    *   **Find this line in `MapActivity.kt` and update it if you have your own key:**
        ```kotlin
        val apiKey = "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6ImZlNDdkYzg3OWZlMDRkZDI4YzZiNGFiMWE1YzhmZTMyIiwiaCI6Im11cm11cjY0In0="
        ```

5.  **Permissions:**
    *   Ensure location permissions are granted when prompted by the app on first launch.

6.  **Run the Application:**
    *   Connect an Android device or start an Android Emulator.
    *   Click the "Run 'app'" button (green play icon) in Android Studio.

## Technologies Used

*   **Kotlin:** Primary programming language.
*   **Android SDK:** For building the Android application.
*   **OSMdroid:** For displaying maps and overlays.
*   **OpenRouteService (ORS):** Routing API for generating diverse routes.
*   **OSRM (Open Source Routing Machine):** Fallback routing service.
*   **Nominatim:** Geocoding service for converting addresses to coordinates.
*   **Material Design:** For a modern and consistent UI.
*   **Coroutines:** For asynchronous operations and network requests.

---


