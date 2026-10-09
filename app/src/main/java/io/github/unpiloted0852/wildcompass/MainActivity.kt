package io.github.unpiloted0852.wildcompass

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private enum class State { WAITING_FOR_LOCATION, NO_PERMISSION, SEARCHING, READY, FAILED }

    private lateinit var prefs: SharedPreferences
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var magneticSensor: Sensor? = null
    private var locationCallback: LocationCallback? = null

    private val updater by lazy { AppUpdater(this) }
    private lateinit var tvUpdate: TextView
    private var askedForNotifications = false
    private lateinit var chipRow: LinearLayout
    private lateinit var btnRecency: TextView
    private lateinit var viewRing: View
    private lateinit var ivArrow: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button
    private lateinit var tvDistance: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCompass: TextView
    private lateinit var card: View
    private lateinit var ivPhoto: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvSci: TextView
    private lateinit var tvBadge: TextView
    private lateinit var tvPhotoCount: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvCredit: TextView

    // Settings
    private var group = TaxonGroup.ANY
    private var recency = Recency.ANY
    private var useINaturalist = true
    private var useGbif = true
    private var researchOnly = false
    private var imperial = false
    private var haptics = true

    // Search
    private var state = State.WAITING_FOR_LOCATION
    private var here: Location? = null
    private var pool: List<Observation> = emptyList()
    private var failedSources: List<Source> = emptyList()
    private val skipped = ArrayDeque<String>()
    private var target: Observation? = null
    private var searchJob: Job? = null
    private var searchCenter: Location? = null
    private var searchingFrom: Location? = null
    private var nearestAtSearchMeters = 0f
    private var lastSearchTime = 0L
    private val commonNames = HashMap<Long, String?>()

    // Compass
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var declinationDeg = 0f
    private var declinationAt: Location? = null
    private var lastMagAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var arrowRotation = 0f
    private var lastArrowUpdate = 0L
    private var lastPulse = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark in both system themes, so the bar icons must always be light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        loadSettings()
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        bindViews()
        buildChips()
        render()
        if (rotationSensor == null) {
            tvCompass.text = "This phone has no compass sensor, so the arrow cannot turn."
            tvCompass.visibility = View.VISIBLE
        }
        if (!hasLocationPermission()) requestLocationPermission()
        checkForUpdate()
    }

    /** Once per launch: if GitHub has a newer release, offer it as a tappable pill at the top. */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val release = updater.checkForUpdate() ?: return@launch
            val offer = "Update available: v${release.versionName}. Tap to install."
            var busy = false
            tvUpdate.text = offer
            tvUpdate.visibility = View.VISIBLE
            tvUpdate.setOnClickListener {
                if (busy) return@setOnClickListener
                // The update closes the app, and Android only lets it offer to reopen through
                // a notification. Ask once; the update goes ahead on the next tap either way.
                if (UpdateReceiver.needsNotification && !UpdateReceiver.canNotify(this@MainActivity) &&
                    !askedForNotifications
                ) {
                    askedForNotifications = true
                    ActivityCompat.requestPermissions(
                        this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS
                    )
                    tvUpdate.text = "Tap again to install v${release.versionName}."
                    return@setOnClickListener
                }
                busy = true
                lifecycleScope.launch {
                    val error = updater.downloadAndInstall(release) { pct ->
                        tvUpdate.text = if (pct < 100) "Downloading update… $pct%" else "Installing update…"
                    }
                    // Only reached if the update did not replace the running app.
                    busy = false
                    tvUpdate.text = offer
                    if (error != null && error != "cancelled") {
                        Toast.makeText(this@MainActivity, "Update failed: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private fun loadSettings() {
        group = TaxonGroup.entries.firstOrNull { it.name == prefs.getString("group", null) } ?: TaxonGroup.ANY
        recency = Recency.entries.firstOrNull { it.name == prefs.getString("recency", null) } ?: Recency.ANY
        useINaturalist = prefs.getBoolean("useINaturalist", true)
        useGbif = prefs.getBoolean("useGbif", true)
        researchOnly = prefs.getBoolean("researchOnly", false)
        // Miles and feet by default only where they are the everyday units.
        imperial = prefs.getBoolean("imperial", Locale.getDefault().country in setOf("US", "GB", "LR", "MM"))
        haptics = prefs.getBoolean("haptics", true)
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("group", group.name)
            .putString("recency", recency.name)
            .putBoolean("useINaturalist", useINaturalist)
            .putBoolean("useGbif", useGbif)
            .putBoolean("researchOnly", researchOnly)
            .putBoolean("imperial", imperial)
            .putBoolean("haptics", haptics)
            .apply()
    }

    private fun bindViews() {
        tvUpdate = findViewById(R.id.tvUpdate)
        chipRow = findViewById(R.id.chipRow)
        btnRecency = findViewById(R.id.btnRecency)
        viewRing = findViewById(R.id.viewRing)
        ivArrow = findViewById(R.id.ivArrow)
        progress = findViewById(R.id.progress)
        btnBack = findViewById(R.id.btnBack)
        btnNext = findViewById(R.id.btnNext)
        tvDistance = findViewById(R.id.tvDistance)
        tvStatus = findViewById(R.id.tvStatus)
        tvCompass = findViewById(R.id.tvCompass)
        card = findViewById(R.id.card)
        ivPhoto = findViewById(R.id.ivPhoto)
        tvName = findViewById(R.id.tvName)
        tvSci = findViewById(R.id.tvSci)
        tvBadge = findViewById(R.id.tvBadge)
        tvPhotoCount = findViewById(R.id.tvPhotoCount)
        tvMeta = findViewById(R.id.tvMeta)
        tvCredit = findViewById(R.id.tvCredit)

        // Round the photo's corners along with the card's.
        card.clipToOutline = true

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val side = (16 * resources.displayMetrics.density).toInt()
            v.updatePadding(
                left = side + bars.left, top = bars.top, right = side + bars.right, bottom = bars.bottom
            )
            insets
        }

        btnRecency.setOnClickListener { showRecencyDialog() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        btnNext.setOnClickListener { skipTarget() }
        btnBack.setOnClickListener { unskip() }
        tvStatus.setOnClickListener { onStatusTapped() }
        ivPhoto.setOnClickListener {
            target?.let { PhotoViewer.show(this, it.photos, it.commonName ?: it.scientificName ?: "Observation") }
        }
        findViewById<View>(R.id.btnOpen).setOnClickListener { target?.let { openUrl(it.recordUrl) } }
        findViewById<View>(R.id.btnMap).setOnClickListener { target?.let { openMap(it) } }
    }

    private fun buildChips() {
        val density = resources.displayMetrics.density
        chipRow.removeAllViews()
        for (g in TaxonGroup.entries) {
            val chip = TextView(this).apply {
                text = "${g.emoji} ${g.label}"
                textSize = 14f
                setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text))
                setBackgroundResource(R.drawable.bg_chip)
                gravity = android.view.Gravity.CENTER
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                isSelected = g == group
                tag = g
                setOnClickListener { selectGroup(g) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * density).toInt())
            lp.marginStart = (4 * density).toInt()
            lp.marginEnd = (4 * density).toInt()
            // 48 dp touch target around a 36 dp pill.
            lp.topMargin = (6 * density).toInt()
            lp.bottomMargin = (6 * density).toInt()
            chipRow.addView(chip, lp)
        }
    }

    private fun selectGroup(g: TaxonGroup) {
        if (g == group) return
        group = g
        for (i in 0 until chipRow.childCount) chipRow.getChildAt(i).let { it.isSelected = it.tag == g }
        saveSettings()
        newSearch()
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private fun showRecencyDialog() {
        val options = Recency.entries
        AlertDialog.Builder(this)
            .setTitle("Observed")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(recency)) { d, which ->
                d.dismiss()
                if (options[which] != recency) {
                    recency = options[which]
                    saveSettings()
                    newSearch()
                }
            }
            .show()
    }

    private fun showSettingsDialog() {
        val labels = arrayOf(
            "Search iNaturalist",
            "Search GBIF (Observation.org, Pl@ntNet and others)",
            "Only confirmed identifications (iNaturalist research grade)",
            "Miles and feet",
            "Vibrate when the arrow lines up",
        )
        val checked = booleanArrayOf(useINaturalist, useGbif, researchOnly, imperial, haptics)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Done") { _, _ ->
                if (!checked[0] && !checked[1]) {
                    Toast.makeText(this, "At least one source is needed.", Toast.LENGTH_SHORT).show()
                    checked[0] = true
                }
                val searchChanged =
                    checked[0] != useINaturalist || checked[1] != useGbif || checked[2] != researchOnly
                useINaturalist = checked[0]
                useGbif = checked[1]
                researchOnly = checked[2]
                imperial = checked[3]
                haptics = checked[4]
                saveSettings()
                if (searchChanged) newSearch() else render()
            }
            .setNeutralButton("About") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("WildCompass ${BuildConfig.VERSION_NAME}")
                    .setMessage(R.string.about_text)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Buy me a coffee") { _, _ -> openUrl(KOFI_URL) }
                    .show()
            }
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open web links.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMap(o: Observation) {
        val label = Uri.encode(o.commonName ?: o.scientificName ?: "Observation")
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:${o.lat},${o.lon}?q=${o.lat},${o.lon}($label)"))
            )
        } catch (e: ActivityNotFoundException) {
            openUrl("https://www.openstreetmap.org/?mlat=${o.lat}&mlon=${o.lon}#map=18/${o.lat}/${o.lon}")
        }
    }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQUEST_LOCATION
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        if (hasLocationPermission()) {
            state = State.WAITING_FOR_LOCATION
            startLocationUpdates()
        } else {
            state = State.NO_PERMISSION
        }
        render()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission() || locationCallback != null) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onNewLocation(it) }
            }
        }
        locationCallback = callback
        fusedLocation.requestLocationUpdates(request, callback, Looper.getMainLooper())
        // Start from the last known position instead of waiting for a fresh fix.
        if (here == null) {
            fusedLocation.lastLocation.addOnSuccessListener { loc ->
                val ageMs = (SystemClock.elapsedRealtimeNanos() - (loc?.elapsedRealtimeNanos ?: 0L)) / 1_000_000
                if (loc != null && here == null && ageMs < MAX_LAST_LOCATION_AGE_MS) onNewLocation(loc)
            }
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocation.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun onNewLocation(loc: Location) {
        here = loc
        updateDeclination(loc)
        when {
            state == State.WAITING_FOR_LOCATION -> newSearch()
            // The search began from a position that turned out to be far off; start over.
            state == State.SEARCHING && (searchingFrom?.distanceTo(loc) ?: 0f) > FAR_MOVE_METERS -> newSearch()
            state == State.READY && movedEnoughToSearchAgain(loc) -> search(keepSkipped = true)
            else -> {
                retarget()
                render()
            }
        }
        // Above walking pace the direction of travel is steadier than the magnetometer.
        if (loc.hasBearing() && loc.speed > DRIVING_SPEED_MPS) updateArrow(loc.bearing)
    }

    /**
     * The answer was exact where it was asked. Ask again once the phone has moved a fair
     * share of the way to the nearest record (something closer may now exist), but not
     * more often than every [MIN_SEARCH_INTERVAL_MS] unless it has moved a long way.
     */
    private fun movedEnoughToSearchAgain(loc: Location): Boolean {
        val center = searchCenter ?: return true
        val moved = loc.distanceTo(center)
        if (moved > FAR_MOVE_METERS) return true
        if (SystemClock.elapsedRealtime() - lastSearchTime < MIN_SEARCH_INTERVAL_MS) return false
        return moved > maxOf(MIN_MOVE_METERS, nearestAtSearchMeters * 0.5f)
    }

    private fun updateDeclination(loc: Location) {
        val last = declinationAt
        if (last != null && last.distanceTo(loc) < 10_000) return
        declinationAt = loc
        declinationDeg = GeomagneticField(
            loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis()
        ).declination
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /** A search for something different: forget the old results and skips. */
    private fun newSearch() {
        pool = emptyList()
        skipped.clear()
        target = null
        search(keepSkipped = false)
    }

    private fun search(keepSkipped: Boolean) {
        val loc = here
        if (loc == null) {
            state = if (hasLocationPermission()) State.WAITING_FOR_LOCATION else State.NO_PERMISSION
            render()
            return
        }
        searchJob?.cancel()
        searchingFrom = loc
        // A refresh while walking keeps showing the current target; a new search does not.
        if (!keepSkipped) state = State.SEARCHING
        render()

        val sources = buildSet {
            if (useINaturalist) add(Source.INATURALIST)
            if (useGbif) add(Source.GBIF)
        }
        searchJob = lifecycleScope.launch {
            val result = try {
                NearestFinder.find(loc.latitude, loc.longitude, group, recency, sources, researchOnly)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            lastSearchTime = SystemClock.elapsedRealtime()
            if (result == null || result.allFailed) {
                // Keep pointing at what is already known if only a refresh failed.
                if (pool.isEmpty()) state = State.FAILED
                render()
                return@launch
            }
            pool = result.observations
            failedSources = result.failed
            searchCenter = loc
            nearestAtSearchMeters = pool.firstOrNull()?.distanceFrom(loc) ?: 0f
            state = State.READY
            retarget()
            render()
        }
    }

    /** Points at the nearest record that has not been skipped. */
    private fun retarget() {
        val loc = here ?: return
        val nearest = pool.asSequence().filter { it.key !in skipped }.minByOrNull { it.distanceFrom(loc) }
        val current = target?.takeIf { cur -> cur.key !in skipped && pool.any { it.key == cur.key } }
        // Stay with the current target unless another is clearly closer, so that two
        // records at similar distances do not swap back and forth with GPS jitter.
        val next = when {
            nearest == null -> null
            current == null -> nearest
            else -> {
                val d = current.distanceFrom(loc)
                if (nearest.distanceFrom(loc) < d - maxOf(SWITCH_MARGIN_METERS, d * 0.2f)) nearest else current
            }
        }
        if (next?.key != target?.key) {
            target = next
            showObservation(next)
        }
    }

    private fun skipTarget() {
        val t = target ?: return
        skipped.addLast(t.key)
        target = null
        retarget()
        render()
    }

    private fun unskip() {
        val key = skipped.removeLastOrNull() ?: return
        target = pool.firstOrNull { it.key == key }
        showObservation(target)
        if (target == null) retarget()
        render()
    }

    private fun onStatusTapped() {
        when (state) {
            State.NO_PERMISSION -> requestLocationPermission()
            State.FAILED -> search(keepSkipped = false)
            else -> {}
        }
    }

    // ------------------------------------------------------------------
    // Display
    // ------------------------------------------------------------------

    private fun showObservation(o: Observation?) {
        if (o == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        tvName.text = o.commonName ?: o.scientificName ?: "Unidentified"
        tvSci.text = o.scientificName?.takeIf { o.commonName != null && it != o.commonName }.orEmpty()
        tvSci.visibility = if (tvSci.text.isEmpty()) View.GONE else View.VISIBLE
        tvBadge.text = o.sourceLabel

        val seen = buildString {
            append("Seen")
            formatDate(o.observedOn)?.let { append(" $it") }
            o.observer?.let { append(" by $it") }
        }.takeIf { it != "Seen" }
        val grade = if (o.researchGrade) "Identification confirmed by the community" else null
        tvMeta.text = listOfNotNull(seen, o.place, grade).joinToString("\n")
        tvMeta.visibility = if (tvMeta.text.isEmpty()) View.GONE else View.VISIBLE
        val photo = o.photos.first()
        tvCredit.text = photo.credit?.let { "Photo $it" }.orEmpty()
        tvCredit.visibility = if (tvCredit.text.isEmpty()) View.GONE else View.VISIBLE

        tvPhotoCount.text = "${o.photos.size} photos"
        tvPhotoCount.visibility = if (o.photos.size > 1) View.VISIBLE else View.GONE

        ivPhoto.load(photo.url) {
            crossfade(true)
            placeholder(ColorDrawable(ContextCompat.getColor(this@MainActivity, R.color.chip)))
            error(ColorDrawable(ContextCompat.getColor(this@MainActivity, R.color.chip)))
            listener(
                onSuccess = { _, result -> PhotoViewer.rememberCardCopy(photo.url, result.memoryCacheKey) },
                onError = { _, _ ->
                val fallback = photo.fullUrls.lastOrNull { it != photo.url }
                if (target?.key == o.key && fallback != null) ivPhoto.load(fallback)
            })
        }

        // GBIF records carry only the scientific name; look the everyday one up once.
        val speciesKey = o.gbifSpeciesKey
        if (o.commonName == null && speciesKey != null) {
            if (commonNames.containsKey(speciesKey)) {
                applyCommonName(o, commonNames[speciesKey])
            } else {
                lifecycleScope.launch {
                    val name = try {
                        GbifClient.commonName(speciesKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@launch
                    }
                    commonNames[speciesKey] = name
                    applyCommonName(o, name)
                }
            }
        }
    }

    private fun applyCommonName(o: Observation, name: String?) {
        if (name == null || target?.key != o.key) return
        tvName.text = name.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.getDefault()) } }
        tvSci.text = o.scientificName.orEmpty()
        tvSci.visibility = if (tvSci.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun formatDate(iso: String?): String? = try {
        iso?.let { LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    } catch (e: Exception) {
        null
    }

    /** Brings the distance, status line, buttons and spinner in line with the current state. */
    private fun render() {
        val loc = here
        val t = target
        btnRecency.text = "${recency.label} ▾"
        progress.visibility = if (state == State.SEARCHING) View.VISIBLE else View.GONE
        btnBack.visibility = if (skipped.isNotEmpty() && state == State.READY) View.VISIBLE else View.INVISIBLE
        btnNext.visibility = if (t != null && state == State.READY) View.VISIBLE else View.INVISIBLE
        setArrowActive(t != null && loc != null && state == State.READY)

        if (state != State.READY || t == null || loc == null) {
            tvDistance.text = ""
            if (state != State.READY) card.visibility = View.GONE
            tvStatus.text = when (state) {
                State.NO_PERMISSION -> "Location access is needed to find what is near you. Tap to allow."
                State.WAITING_FOR_LOCATION -> "Waiting for your location…"
                State.SEARCHING -> "Looking for ${group.noun} near you…"
                State.FAILED -> "Can't reach iNaturalist or GBIF. Tap to try again."
                State.READY ->
                    if (skipped.isNotEmpty()) "That was the last one nearby. Go back, or change the filter."
                    else "No ${group.noun} with a photo found within 300 km" +
                            (if (recency == Recency.ANY) "." else " in the ${recency.label.lowercase()}.")
            }
            return
        }

        val meters = t.distanceFrom(loc)
        tvDistance.text = if (meters <= maxOf(ARRIVED_METERS, loc.accuracy.coerceAtMost(30f))) "You're here" else formatDistance(meters)
        val others = pool.size - skipped.size - 1
        tvStatus.text = buildString {
            append(if (skipped.isEmpty()) "Nearest ${singular()}" else "Next nearest (${skipped.size} skipped)")
            if (others > 0) append(" · $others more nearby")
            if (failedSources.isNotEmpty()) {
                append("\n")
                append(failedSources.joinToString(" and ") { if (it == Source.GBIF) "GBIF" else "iNaturalist" })
                append(" did not answer; showing the rest.")
            }
        }
    }

    private fun singular(): String = when (group) {
        TaxonGroup.ANY -> "observation"
        TaxonGroup.BIRDS -> "bird"
        TaxonGroup.MAMMALS -> "mammal"
        TaxonGroup.REPTILES -> "reptile"
        TaxonGroup.AMPHIBIANS -> "amphibian"
        TaxonGroup.FISH -> "fish"
        TaxonGroup.INSECTS -> "insect"
        TaxonGroup.SPIDERS -> "spider"
        TaxonGroup.MOLLUSCS -> "snail or shell"
        TaxonGroup.PLANTS -> "plant"
        TaxonGroup.FUNGI -> "fungus"
    }

    private fun formatDistance(meters: Float): String =
        if (imperial) {
            val feet = meters * 3.28084f
            if (feet < 1000) "${feet.roundToInt()} ft"
            else String.format(Locale.getDefault(), if (feet < 52800) "%.2f mi" else "%.1f mi", feet / 5280f)
        } else {
            if (meters < 1000) "${meters.roundToInt()} m"
            else String.format(Locale.getDefault(), if (meters < 10000) "%.2f km" else "%.1f km", meters / 1000f)
        }

    // ------------------------------------------------------------------
    // Sensors & arrow
    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magneticSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (hasLocationPermission()) {
            if (state == State.NO_PERMISSION) state = State.WAITING_FOR_LOCATION
            startLocationUpdates()
        } else {
            state = State.NO_PERMISSION
        }
        render()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            lastMagAccuracy = event.accuracy
            return
        }
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        if ((here?.speed ?: 0f) > DRIVING_SPEED_MPS && here?.hasBearing() == true) return // GPS heading in charge

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        var axisX = SensorManager.AXIS_X
        var axisY = SensorManager.AXIS_Y
        when (currentDisplayRotation()) {
            Surface.ROTATION_90 -> { axisX = SensorManager.AXIS_Y; axisY = SensorManager.AXIS_MINUS_X }
            Surface.ROTATION_180 -> { axisX = SensorManager.AXIS_MINUS_X; axisY = SensorManager.AXIS_MINUS_Y }
            Surface.ROTATION_270 -> { axisX = SensorManager.AXIS_MINUS_Y; axisY = SensorManager.AXIS_X }
        }
        if (SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)) {
            SensorManager.getOrientation(remappedMatrix, orientationAngles)
        } else {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
        }

        // The rotation vector is relative to magnetic north; bearings are relative to true north.
        val magneticAzimuth = (Math.toDegrees(orientationAngles[0].toDouble()) + 360).toFloat() % 360
        updateArrow((magneticAzimuth + declinationDeg + 360) % 360)
        updateCompassWarning(event)
    }

    private fun currentDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

    private fun updateCompassWarning(event: SensorEvent) {
        val poor = if (event.values.size > 4 && event.values[4] != -1f) {
            event.values[4] >= 0.8f
        } else {
            lastMagAccuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        }
        if (poor) tvCompass.text = "Compass is unsure. Move the phone in a figure of eight."
        tvCompass.visibility = if (poor) View.VISIBLE else View.GONE
    }

    /**
     * Turns the arrow toward the target with time-based exponential smoothing, and gives
     * a short pulse when it lines up with the top of the phone.
     */
    private fun updateArrow(heading: Float) {
        val loc = here ?: return
        val t = target ?: return
        if (state != State.READY) return
        val wanted = (t.bearingFrom(loc) - heading + 720) % 360

        var diff = wanted - arrowRotation
        while (diff < -180) diff += 360
        while (diff > 180) diff -= 360
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastArrowUpdate == 0L) 0.016f else (now - lastArrowUpdate).coerceAtMost(100L) / 1000f
        lastArrowUpdate = now
        arrowRotation += diff * (1f - exp(-dt * 10f))
        ivArrow.rotation = arrowRotation

        val off = if (wanted > 180) wanted - 360 else wanted
        if (abs(off) < ALIGNED_DEGREES) alignmentPulse()
    }

    private fun alignmentPulse() {
        if (!haptics) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPulse < 1500) return
        lastPulse = now
        try {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(30, 80))
        } catch (e: Exception) {
            // no vibrator -- fine
        }
    }

    private fun setArrowActive(active: Boolean) {
        ivArrow.alpha = if (active) 1f else 0.35f
        ivArrow.setColorFilter(ContextCompat.getColor(this, if (active) R.color.accent else R.color.arrow_idle))
        viewRing.alpha = if (active) 1f else 0.5f
    }

    private companion object {
        const val REQUEST_LOCATION = 1
        const val REQUEST_NOTIFICATIONS = 2
        const val KOFI_URL = "https://ko-fi.com/unpiloted0852"
        const val DRIVING_SPEED_MPS = 4f
        const val ALIGNED_DEGREES = 12f
        const val ARRIVED_METERS = 8f
        const val SWITCH_MARGIN_METERS = 15f
        const val MIN_MOVE_METERS = 100f
        const val MIN_SEARCH_INTERVAL_MS = 20_000L
        const val FAR_MOVE_METERS = 1000f
        const val MAX_LAST_LOCATION_AGE_MS = 10 * 60 * 1000L
    }
}
