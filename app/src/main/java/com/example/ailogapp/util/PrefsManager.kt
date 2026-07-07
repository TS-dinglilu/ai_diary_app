package com.example.ailogapp.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 偏好配置：AI 接口地址、API Key、模型名称、转写模式与接口地址、切分时长等。
 * 通过 UI 保存，供 Worker / AI 模块读取。
 */
class PrefsManager(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("ailog_prefs", Context.MODE_PRIVATE)

    /** AI 接口地址（OpenAI 兼容的 /v1/chat/completions） */
    var aiApiUrl: String
        get() = sp.getString(KEY_AI_URL, "https://api.openai.com/v1/chat/completions") ?: ""
        set(value) = sp.edit { putString(KEY_AI_URL, value) }

    var aiApiKey: String
        get() = sp.getString(KEY_AI_KEY, "") ?: ""
        set(value) = sp.edit { putString(KEY_AI_KEY, value) }

    var aiModel: String
        get() = sp.getString(KEY_AI_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = sp.edit { putString(KEY_AI_MODEL, value) }

    // ---- 转写配置 ----

    /**
     * 转写模式：
     * - "off"    → 关闭转写，仅保存录音
     * - "local"  → 内置模型：使用 sherpa-onnx 离线引擎，完全本地运行，无需网络
     * - "whisper" → 调用 Whisper 兼容接口（multipart 上传音频）
     * - "ai"     → 调用 AI 大模型接口转写（将音频以 base64 发送给 LLM）
     */
    var transcribeMode: String
        get() = sp.getString(KEY_TRANSCRIBE_MODE, "off") ?: "off"
        set(value) = sp.edit { putString(KEY_TRANSCRIBE_MODE, value) }

    /** Whisper 兼容转写接口地址（/v1/audio/transcriptions） */
    var transcribeUrl: String
        get() = sp.getString(KEY_TRANSCRIBE_URL, "") ?: ""
        set(value) = sp.edit { putString(KEY_TRANSCRIBE_URL, value) }

    /** 转写使用的模型名（Whisper 模式下默认 whisper-1；AI 模式下用 [aiModel]） */
    var transcribeModel: String
        get() = sp.getString(KEY_TRANSCRIBE_MODEL, "whisper-1") ?: "whisper-1"
        set(value) = sp.edit { putString(KEY_TRANSCRIBE_MODEL, value) }

    // ---- AI 分析配置 ----

    /**
     * AI 分析模式：
     * - "cloud" → 云端大模型分析（需要配置 AI 接口）
     * - "local" → 本地离线大模型分析（Gemma-2 2B int8，基于 MediaPipe，无需网络）
     */
    var analyzeMode: String
        get() = sp.getString(KEY_ANALYZE_MODE, "cloud") ?: "cloud"
        set(value) = sp.edit { putString(KEY_ANALYZE_MODE, value) }

    // ---- 充电自动触发开关 ----

    /** 充电时是否自动语音转文字，默认开启 */
    var autoTranscribeOnCharging: Boolean
        get() = sp.getBoolean(KEY_AUTO_TRANSCRIBE, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_TRANSCRIBE, value) }

    /** 充电时是否自动 AI 分析，默认关闭（避免端侧模型长时间占用资源） */
    var autoAnalyzeOnCharging: Boolean
        get() = sp.getBoolean(KEY_AUTO_ANALYZE, false)
        set(value) = sp.edit { putBoolean(KEY_AUTO_ANALYZE, value) }

    // ---- 录音配置 ----

    /** 单段录音切分时长（分钟），默认 3。getter 强制最小为 1，避免 0/负值导致疯狂切段。 */
    var segmentMinutes: Int
        get() = sp.getInt(KEY_SEGMENT_MIN, 3).coerceAtLeast(1)
        set(value) = sp.edit { putInt(KEY_SEGMENT_MIN, value) }

    /** 录音比特率(bps)，默认 32000，保证文件小且适合转写 */
    var bitRate: Int
        get() = sp.getInt(KEY_BITRATE, 32000)
        set(value) = sp.edit { putInt(KEY_BITRATE, value) }

    var recordingEnabled: Boolean
        get() = sp.getBoolean(KEY_REC_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_REC_ENABLED, value) }

    /** 本次录音启动时间戳(ms)，用于 UI 显示累计时长 */
    var recordingStartTime: Long
        get() = sp.getLong(KEY_REC_START, 0L)
        set(value) = sp.edit { putLong(KEY_REC_START, value) }

    // ---- 定时录音配置 ----

    /** 是否启用定时录音（按设定时间段自动启停录音），默认 false */
    var scheduleRecordingEnabled: Boolean
        get() = sp.getBoolean(KEY_SCHEDULE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_SCHEDULE_ENABLED, value) }

    /** 定时录音自动启动时间，格式 HH:mm（24 小时制），默认 "07:30" */
    var scheduleRecordStartTime: String
        get() = sp.getString(KEY_SCHEDULE_START, "07:30") ?: "07:30"
        set(value) = sp.edit { putString(KEY_SCHEDULE_START, value) }

    /** 定时录音自动停止时间，格式 HH:mm（24 小时制），默认 "23:00" */
    var scheduleRecordStopTime: String
        get() = sp.getString(KEY_SCHEDULE_STOP, "23:00") ?: "23:00"
        set(value) = sp.edit { putString(KEY_SCHEDULE_STOP, value) }

    /**
     * 当前正在录音的文件路径（用于进程被杀后恢复）。
     * 使用 commit() 同步写入：apply() 异步刷盘，进程被强杀时可能丢失，
     * 导致孤儿录音无法恢复（这正是该字段的核心使用场景）。
     */
    var currentRecordingPath: String
        get() = sp.getString(KEY_CUR_REC_PATH, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_CUR_REC_PATH, value).commit()
        }

    /** 当前正在录音的段开始时间戳(ms)（用于进程被杀后恢复）。同步写入，原因同上。 */
    var currentRecordingStartMs: Long
        get() = sp.getLong(KEY_CUR_REC_START_MS, 0L)
        set(value) {
            sp.edit().putLong(KEY_CUR_REC_START_MS, value).commit()
        }

    var analysisHistoryDays: Int
        get() = sp.getInt(KEY_HISTORY_DAYS, 7)
        set(value) = sp.edit { putInt(KEY_HISTORY_DAYS, value) }

    // ---- 自动删除配置 ----

    /** 是否启用自动删除录音（只删录音文件，保留转写文字） */
    var autoDeleteEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_DELETE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_AUTO_DELETE_ENABLED, value) }

    /** 自动删除录音的天数（超过N天的录音文件会被自动删除，保留转写文字） */
    var autoDeleteDays: Int
        get() = sp.getInt(KEY_AUTO_DELETE_DAYS, 7).coerceAtLeast(1)
        set(value) = sp.edit { putInt(KEY_AUTO_DELETE_DAYS, value) }

    // ---- 界面配置 ----

    /**
     * AI 分析界面日期是否默认折叠录音条目。
     * true=默认折叠（只显示日期头部和 AI 分析/笔记），false=默认展开（显示所有录音）。
     */
    var defaultCollapseRecords: Boolean
        get() = sp.getBoolean(KEY_DEFAULT_COLLAPSE, true)
        set(value) = sp.edit { putBoolean(KEY_DEFAULT_COLLAPSE, value) }

    /**
     * 主题模式：
     * - "system" → 跟随系统
     * - "light"  → 浅色
     * - "dark"   → 深色
     */
    var themeMode: String
        get() = sp.getString(KEY_THEME_MODE, THEME_LIGHT) ?: THEME_LIGHT
        set(value) = sp.edit { putString(KEY_THEME_MODE, value) }

    // ---- 模型下载链接 ----

    /** 本地语音转文字模型下载链接（用户可填写 GitHub Release 等链接） */
    var asrModelUrl: String
        get() = sp.getString(KEY_ASR_MODEL_URL, DEFAULT_ASR_MODEL_URL) ?: DEFAULT_ASR_MODEL_URL
        set(value) = sp.edit { putString(KEY_ASR_MODEL_URL, value) }

    /** 本地离线 AI 分析模型下载链接（用户可填写 GitHub Release 等链接） */
    var llmModelUrl: String
        get() = sp.getString(KEY_LLM_MODEL_URL, DEFAULT_LLM_MODEL_URL) ?: DEFAULT_LLM_MODEL_URL
        set(value) = sp.edit { putString(KEY_LLM_MODEL_URL, value) }

    // ---- WebDAV 云备份配置 ----

    /** WebDAV 服务器地址，默认坚果云 */
    var webdavUrl: String
        get() = sp.getString(KEY_WEBDAV_URL, DEFAULT_WEBDAV_URL) ?: DEFAULT_WEBDAV_URL
        set(value) = sp.edit { putString(KEY_WEBDAV_URL, value) }

    /** WebDAV 账号邮箱，默认坚果云账号 */
    var webdavEmail: String
        get() = sp.getString(KEY_WEBDAV_EMAIL, DEFAULT_WEBDAV_EMAIL) ?: DEFAULT_WEBDAV_EMAIL
        set(value) = sp.edit { putString(KEY_WEBDAV_EMAIL, value) }

    /** WebDAV 访问密钥（应用密码），默认坚果云密钥 */
    var webdavPassword: String
        get() = sp.getString(KEY_WEBDAV_PASSWORD, DEFAULT_WEBDAV_PASSWORD) ?: DEFAULT_WEBDAV_PASSWORD
        set(value) = sp.edit { putString(KEY_WEBDAV_PASSWORD, value) }

    /** 充电时是否自动备份到 WebDAV，默认关闭 */
    var webdavAutoBackup: Boolean
        get() = sp.getBoolean(KEY_WEBDAV_AUTO_BACKUP, false)
        set(value) = sp.edit { putBoolean(KEY_WEBDAV_AUTO_BACKUP, value) }

    /** 启动 App 时是否自动备份到坚果云，默认开启 */
    var autoBackupOnAppStart: Boolean
        get() = sp.getBoolean(KEY_AUTO_BACKUP_ON_START, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_BACKUP_ON_START, value) }

    /** 上次自动备份的时间戳（毫秒），用于避免短时间内重复备份 */
    var lastAutoBackupTime: Long
        get() = sp.getLong(KEY_LAST_AUTO_BACKUP_TIME, 0L)
        set(value) = sp.edit { putLong(KEY_LAST_AUTO_BACKUP_TIME, value) }

    companion object {
        private const val KEY_AI_URL = "ai_api_url"
        private const val KEY_AI_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_TRANSCRIBE_MODE = "transcribe_mode"
        private const val KEY_TRANSCRIBE_URL = "transcribe_url"
        private const val KEY_TRANSCRIBE_MODEL = "transcribe_model"
        private const val KEY_ANALYZE_MODE = "analyze_mode"
        private const val KEY_AUTO_TRANSCRIBE = "auto_transcribe_charging"
        private const val KEY_AUTO_ANALYZE = "auto_analyze_charging"
        private const val KEY_SEGMENT_MIN = "segment_minutes"
        private const val KEY_BITRATE = "bit_rate"
        private const val KEY_REC_ENABLED = "recording_enabled"
        private const val KEY_REC_START = "recording_start_time"
        private const val KEY_SCHEDULE_ENABLED = "schedule_recording_enabled"
        private const val KEY_SCHEDULE_START = "schedule_record_start_time"
        private const val KEY_SCHEDULE_STOP = "schedule_record_stop_time"
        private const val KEY_CUR_REC_PATH = "current_recording_path"
        private const val KEY_CUR_REC_START_MS = "current_recording_start_ms"
        private const val KEY_HISTORY_DAYS = "history_days"
        private const val KEY_AUTO_DELETE_ENABLED = "auto_delete_enabled"
        private const val KEY_AUTO_DELETE_DAYS = "auto_delete_days"
        private const val KEY_DEFAULT_COLLAPSE = "default_collapse_records"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ASR_MODEL_URL = "asr_model_url"
        private const val KEY_LLM_MODEL_URL = "llm_model_url"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_EMAIL = "webdav_email"
        private const val KEY_WEBDAV_PASSWORD = "webdav_password"
        private const val KEY_WEBDAV_AUTO_BACKUP = "webdav_auto_backup"
        private const val KEY_AUTO_BACKUP_ON_START = "auto_backup_on_start"
        private const val KEY_LAST_AUTO_BACKUP_TIME = "last_auto_backup_time"

        /** ModelScope 直链默认值 */
        const val DEFAULT_ASR_MODEL_URL =
            "https://www.modelscope.cn/models/dingliu/ai_diary_app/resolve/master/sense-voice.onnx"
        const val DEFAULT_LLM_MODEL_URL =
            "https://www.modelscope.cn/models/dingliu/ai_diary_app/resolve/master/gemma-2b-it.bin"

        /** 坚果云 WebDAV 默认配置（用户需自行填写，不内置凭据） */
        const val DEFAULT_WEBDAV_URL = "https://dav.jianguoyun.com/dav/"
        const val DEFAULT_WEBDAV_EMAIL = ""
        const val DEFAULT_WEBDAV_PASSWORD = ""

        const val MODE_OFF = "off"
        const val MODE_LOCAL = "local"
        const val MODE_WHISPER = "whisper"
        const val MODE_AI = "ai"
        const val ANALYZE_CLOUD = "cloud"
        const val ANALYZE_LOCAL = "local"

        /** 主题模式常量 */
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}
