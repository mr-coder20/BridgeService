package bah.saj.am.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import bah.saj.am.AppConfig
import bah.saj.am.AppConfig.VPN
import bah.saj.am.PreCheckActivity
import bah.saj.am.R
import bah.saj.am.databinding.ActivityMainBinding
import bah.saj.am.dto.EConfigType
import bah.saj.am.extension.toast
import bah.saj.am.extension.toastError
import bah.saj.am.handler.AngConfigManager
import bah.saj.am.handler.MigrateManager
import bah.saj.am.handler.MmkvManager
import bah.saj.am.handler.V2RayServiceManager
import bah.saj.am.helper.SimpleItemTouchHelperCallback
import bah.saj.am.util.Utils
import bah.saj.am.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
private var connectionMonitorJob: Job? = null
class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val repository by lazy { bah.saj.am.data.MikroTikRepository(this) } // Add this line
    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                startV2Ray()
            }
        }
    private val requestSubSettingActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            initGroupTab()
        }
    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val selectId = tab?.tag.toString()
            if (selectId != mainViewModel.subscriptionId) {
                mainViewModel.subscriptionIdChanged(selectId)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab?) {
        }

        override fun onTabReselected(tab: TabLayout.Tab?) {
        }
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    // register activity result for requesting permission
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                when (pendingAction) {
                    Action.IMPORT_QR_CODE_CONFIG ->
                        scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))

                    Action.READ_CONTENT_FROM_URI ->
                        chooseFileForCustomConfig.launch(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }, getString(R.string.title_file_chooser)))

                    Action.POST_NOTIFICATIONS -> {}
                    else -> {}
                }
            } else {
                toast(R.string.toast_permission_denied)
            }
            pendingAction = Action.NONE
        }

    private var pendingAction: Action = Action.NONE

    enum class Action {
        NONE,
        IMPORT_QR_CODE_CONFIG,
        READ_CONTENT_FROM_URI,
        POST_NOTIFICATIONS
    }

    private val chooseFileForCustomConfig =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val uri = it.data?.data
            if (it.resultCode == RESULT_OK && uri != null) {
                readContentFromUri(uri)
            }
        }

    private val scanQRCodeForConfig =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.title_server)
        setSupportActionBar(binding.toolbar)
        autoConnectRadiusConfig()
        // -----------------------------------

        binding.fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                V2RayServiceManager.stopVService(this)
            } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            } else {
//                tv_test_state.text = getString(R.string.connection_test_fail)
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
        }
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        initGroupTab()
        setupViewModel()
        migrateLegacy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingAction = Action.POST_NOTIFICATIONS
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                binding.fab.setImageResource(R.drawable.ic_stop_24dp)
                binding.fab.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
            } else {
                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                binding.fab.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MigrateManager.migrateServerConfig2Profile()
            launch(Dispatchers.Main) {
                if (result) {
                    toast(getString(R.string.migration_success))
                    mainViewModel.reloadServerList()
                } else {
                    //toast(getString(R.string.migration_fail))
                }
            }

        }
    }

    private fun initGroupTab() {
        binding.tabGroup.removeOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.removeAllTabs()
        binding.tabGroup.isVisible = false

        val (listId, listRemarks) = mainViewModel.getSubscriptions(this)
        if (listId == null || listRemarks == null) {
            return
        }

        for (it in listRemarks.indices) {
            val tab = binding.tabGroup.newTab()
            tab.text = listRemarks[it]
            tab.tag = listId[it]
            binding.tabGroup.addTab(tab)
        }
        val selectIndex =
            listId.indexOf(mainViewModel.subscriptionId).takeIf { it >= 0 } ?: (listId.count() - 1)
        binding.tabGroup.selectTab(binding.tabGroup.getTabAt(selectIndex))
        binding.tabGroup.addOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.isVisible = true
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.ping_all -> {
            toast(
                getString(
                    R.string.connection_test_testing_count,
                    mainViewModel.serversCache.count()
                )
            )
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            toast(
                getString(
                    R.string.connection_test_testing_count,
                    mainViewModel.serversCache.count()
                )
            )
            mainViewModel.testAllRealPing()
            true
        }

        R.id.intelligent_selection_all -> {
            if (MmkvManager.decodeSettingsString(
                    AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD,
                    "1"
                ) != "0"
            ) {
                toast(getString(R.string.pre_resolving_domain))
            }
            mainViewModel.createIntelligentSelectionAll()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }


        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        startActivity(
            Intent()
                .putExtra("createConfigType", createConfigType)
                .putExtra("subscriptionId", mainViewModel.subscriptionId)
                .setClass(this, ServerActivity::class.java)
        )
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
        } else {
            pendingAction = Action.IMPORT_QR_CODE_CONFIG
            requestPermissionLauncher.launch(permission)
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(
                    server,
                    mainViewModel.subscriptionId,
                    true
                )
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> initGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    binding.pbWaiting.hide()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    private fun importConfigViaSub(): Boolean {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val count = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) {
                    toast(getString(R.string.title_update_config_count, count))
                    mainViewModel.reloadServerList()
                } else {
                    toastError(R.string.toast_failure)
                }
                binding.pbWaiting.hide()
            }
        }
        return true
    }

    private fun exportAll() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                binding.pbWaiting.hide()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                binding.pbWaiting.hide()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            pendingAction = Action.READ_CONTENT_FROM_URI
            chooseFileForCustomConfig.launch(
                Intent.createChooser(
                    intent,
                    getString(R.string.title_file_chooser)
                )
            )
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    importBatchConfig(input?.bufferedReader()?.readText())
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

//    val mConnection = object : ServiceConnection {
//        override fun onServiceDisconnected(name: ComponentName?) {
//        }
//
//        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
//            sendMsg(AppConfig.MSG_REGISTER_CLIENT, "")
//        }
//    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.nav_logout -> {
                binding.drawerLayout.closeDrawers()

                AlertDialog.Builder(this)
                    .setTitle("خروج از حساب")
                    .setMessage("آیا اطمینان دارید که می‌خواهید خارج شوید؟")
                    .setPositiveButton("بله") { _, _ ->
                        // توقف سرویس VPN قبل از خروج
                        V2RayServiceManager.stopVService(this)

                        repository.setManualLogout(true)
                        val intent = Intent(this, PreCheckActivity::class.java)
                        intent.flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                    .setNegativeButton("خیر", null)
                    .show()

                return true
            }


            R.id.sub_setting -> requestSubSettingActivity.launch(
                Intent(
                    this,
                    SubSettingActivity::class.java
                )
            )

            R.id.per_app_proxy_settings -> startActivity(
                Intent(
                    this,
                    PerAppProxyActivity::class.java
                )
            )

            R.id.routing_setting -> requestSubSettingActivity.launch(
                Intent(
                    this,
                    RoutingSettingActivity::class.java
                )
            )

            R.id.user_asset_setting -> startActivity(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra("isRunning", mainViewModel.isRunning.value == true)
            )

            R.id.promotion -> Utils.openUri(
                this,
                "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}"
            )

            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    // این تابع را به انتهای کلاس MainActivity اضافه کنید
    // این تابع را در MainActivity.kt پیدا کرده و با کد زیر جایگزین کنید
    // -------------------------------------------------------
    // 1. تابع اصلی اتصال هوشمند (تنظیم شده روی یوتیوب)
    // -------------------------------------------------------
    private fun autoConnectRadiusConfig() {
        // بررسی مجوز VPN
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            requestVpnPermission.launch(prepare)
            return
        }

        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // خاموش کردن سرویس اگر روشن است
                if (mainViewModel.isRunning.value == true) {
                    V2RayServiceManager.stopVService(this@MainActivity)
                    delay(1000)
                }

                // دریافت و آپدیت کانفیگ‌ها
                val status = repository.checkInternetConnectivity()
                if (status.isReachable) {
                    val newConfigs = repository.getDecryptedConfigs()
                    Log.d("AutoConnect", "Configs prepared: ${newConfigs.size}")

                    if (newConfigs.isNotEmpty()) {
                        val targetSubId = ""
                        var totalImported = 0

                        for (configContent in newConfigs) {
                            val (count, _) = AngConfigManager.importBatchConfig(configContent, targetSubId, false)
                            if (count > 0) totalImported++
                        }
                        Log.d("AutoConnect", "Successfully Imported: $totalImported")

                        if (totalImported > 0) {
                            withContext(Dispatchers.Main) { mainViewModel.reloadServerList() }
                            delay(500)

                            if (mainViewModel.serversCache.isEmpty()) {
                                mainViewModel.subscriptionId = ""
                                withContext(Dispatchers.Main) { mainViewModel.reloadServerList() }
                                delay(200)
                            }

                            mainViewModel.removeDuplicateServer()
                            withContext(Dispatchers.Main) { mainViewModel.reloadServerList() }
                            delay(200)

                            // نگه داشتن فقط 4 تای آخر
                            var currentConfigs = mainViewModel.serversCache
                            if (currentConfigs.size > 4) {
                                val deleteCount = currentConfigs.size - 4
                                for (i in 0 until deleteCount) {
                                    currentConfigs.getOrNull(i)?.let {
                                        mainViewModel.removeServer(it.guid)
                                    }
                                }
                                withContext(Dispatchers.Main) { mainViewModel.reloadServerList() }
                            }
                        }
                    }
                }

                // شروع حلقه تست و اتصال
                val serversToCheck = mainViewModel.serversCache.reversed()

                if (serversToCheck.isEmpty()) {
                    Log.e("AutoConnect", "No servers found to connect.")
                    withContext(Dispatchers.Main) { toast("No servers available") }
                    return@launch
                }

                var connectedSuccessfully = false

                for ((index, profile) in serversToCheck.withIndex()) {
                    Log.d("AutoConnect", "Testing server ${index + 1}: GUID=${profile.guid}")

                    withContext(Dispatchers.Main) {
                        toast("Connecting to server ${index + 1}...")
                    }

                    MmkvManager.setSelectServer(profile.guid)

                    withContext(Dispatchers.Main) {
                        V2RayServiceManager.startVService(this@MainActivity)
                    }

                    // تاخیر برای لود کامل هسته V2Ray
                    delay(8000)

                    // *** تست اتصال فقط با یوتیوب ***
                    val isWorking = testRealConnection("https://www.youtube.com", 10000)

                    if (isWorking) {
                        Log.d("AutoConnect", "Connection Successful! (YouTube reachable)")
                        withContext(Dispatchers.Main) {
                            toast(R.string.toast_success)
                        }
                        connectedSuccessfully = true

                        // شروع مانیتورینگ
                        startConnectionMonitor()

                        break
                    } else {
                        Log.w("AutoConnect", "Ping Failed (YouTube unreachable). Stopping service...")
                        withContext(Dispatchers.Main) {
                            V2RayServiceManager.stopVService(this@MainActivity)
                        }
                        delay(1500)
                    }
                }

                if (!connectedSuccessfully) {
                    withContext(Dispatchers.Main) {
                        toast("Connection failed on all servers.")
                    }
                }

            } catch (e: Exception) {
                Log.e("AutoConnect", "Critical Error", e)
                withContext(Dispatchers.Main) { toast("Error: ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) { binding.pbWaiting.hide() }
            }
        }
    }

    // -------------------------------------------------------
    // 2. تابع تست اتصال دقیق با پروکسی (تضمین عبور از تونل)
    // -------------------------------------------------------
    // -------------------------------------------------------
    // 2. تابع تست اتصال اصلاح شده (استفاده از SOCKS روی 10808)
    // -------------------------------------------------------
    private fun testRealConnection(urlStr: String, timeout: Int): Boolean {
        var urlConnection: java.net.HttpURLConnection? = null
        try {
            val url = java.net.URL(urlStr)

            // *** تغییر مهم: استفاده از Proxy.Type.SOCKS و پورت 10808 ***
            // چون کانفیگ‌های فرگمنت اغلب فقط پورت SOCKS (10808) را دارند و HTTP (10809) ندارند.
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", 10808))

            urlConnection = url.openConnection(proxy) as java.net.HttpURLConnection

            urlConnection.connectTimeout = timeout
            urlConnection.readTimeout = timeout
            urlConnection.useCaches = false
            urlConnection.instanceFollowRedirects = true
            urlConnection.requestMethod = "HEAD"

            // هدر User-Agent برای جلوگیری از بلاک شدن توسط گوگل/یوتیوب
            urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 10; Mobile; rv:68.0) Gecko/68.0 Firefox/68.0")
            urlConnection.setRequestProperty("Connection", "close")

            val responseCode = urlConnection.responseCode
            Log.d("PingTest", "Response from $urlStr using SOCKS:10808 -> $responseCode")

            // هر کد موفقیتی (200, 204, 302, etc) قبول است
            return responseCode in 200..399

        } catch (e: Exception) {
            Log.e("PingTest", "Check Failed on SOCKS:10808: ${e.message}")
            // اگر SOCKS هم کار نکرد، یک شانس کوچک به HTTP روی 10809 بدهیم (شاید کانفیگ قدیمی باشد)
            return testRealConnectionFallbackHttp(urlStr, timeout)
        } finally {
            urlConnection?.disconnect()
        }
    }

    // تابع پشتیبان (اگر SOCKS کار نکرد، HTTP را تست می‌کند)
    private fun testRealConnectionFallbackHttp(urlStr: String, timeout: Int): Boolean {
        var urlConnection: java.net.HttpURLConnection? = null
        try {
            val url = java.net.URL(urlStr)
            val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", 10809))
            urlConnection = url.openConnection(proxy) as java.net.HttpURLConnection
            urlConnection.connectTimeout = timeout / 2 // تایم کمتر برای فال‌بک
            urlConnection.readTimeout = timeout / 2
            urlConnection.requestMethod = "HEAD"
            return urlConnection.responseCode in 200..399
        } catch (e: Exception) {
            return false
        } finally {
            urlConnection?.disconnect()
        }
    }


    // -------------------------------------------------------
    // 3. مانیتورینگ اتصال (فقط یوتیوب)
    // -------------------------------------------------------
    private fun startConnectionMonitor() {
        // 1. اطمینان از اینکه مانیتور قبلی کنسل شده است
        connectionMonitorJob?.cancel()

        // 2. اطمینان از اینکه اکتیویتی زنده است
        if (isFinishing || isDestroyed) return

        connectionMonitorJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d("ConnectionMonitor", "Monitoring started (YouTube only)...")

            while (isActive) {
                // وقفه 20 ثانیه‌ای (برای کاهش مصرف باتری عدد خوبی است)
                delay(20000)

                // اگر اکتیویتی در حال بسته شدن است، حلقه را بشکن
                if (!isActive) break

                // فقط اگر VPN روشن است چک کن
                if (mainViewModel.isRunning.value == true) {
                    val isConnected = testRealConnection("https://www.youtube.com", 5000)

                    if (!isConnected) {
                        Log.w("ConnectionMonitor", "YouTube unreachable! Reconnecting...")

                        // چک کردن اینکه آیا اکتیویتی هنوز وجود دارد که Toast نشان دهد
                        if (isActive) {
                            withContext(Dispatchers.Main) {
                                try {
                                    toast("اتصال ناپایدار است. تلاش برای یافتن سرور بهتر...")
                                    V2RayServiceManager.stopVService(this@MainActivity)
                                } catch (e: Exception) {
                                    // جلوگیری از کرش اگر اکتیویتی در لحظه بسته شدن باشد
                                }
                            }
                        }

                        delay(3000) // کمی صبر بیشتر برای بسته شدن کامل سرویس

                        if (isActive) {
                            withContext(Dispatchers.Main) {
                                autoConnectRadiusConfig()
                            }
                        }

                        // خروج از این حلقه (چون autoConnectRadiusConfig خودش مانیتور جدید می‌سازد)
                        break
                    } else {
                        Log.d("ConnectionMonitor", "Connection is stable.")
                    }
                } else {
                    Log.d("ConnectionMonitor", "VPN is OFF. Stopping monitor.")
                    break
                }
            }
        }
    }




}