package com.example.game
// Android 그래픽, 미디어, UI 관련 라이브러리 임포트
import android.content.Context
import android.graphics.*
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList // 멀티스레드 안전 리스트
import kotlin.math.*
/**
 * MainActivity
 * - 앱의 진입점(Entry Point)
 * - 게임 뷰(GameView) 초기화 및 버튼 이벤트 연결
 * - 배경음악(BGM) 생명주기 관리 (onPause/onResume/onDestroy)
 */
class MainActivity : AppCompatActivity() {
    private lateinit var gameView: GameView
    private var mediaPlayer: MediaPlayer? = null // BGM 재생용 (nullable: 파일 없을경우 대비)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 전체화면 몰입 모드 설정: 상태바·네비게이션바 숨김으로 게임 화면 최대화
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        setContentView(R.layout.activity_main)
        // BGM 초기화: res/raw/bgm_main.mp3 파일을 루프 재생
        // try-catch로 감싸 파일 미존재 시 앱 크래시 방지
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.bgm_main).apply {
                isLooping = true // 곡 끝나면 자동 반복
                setVolume(0.5f, 0.5f) // 좌우 채널 볼륨 50%
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }
        gameView = findViewById(R.id.gameView)
        gameView.soundSynthesizer = SoundSynthesizer() // 효과음 합성기 주입
        // 하단 버튼 이벤트 연결
        findViewById<Button>(R.id.btnLeft).setOnClickListener { gameView.movePlayer(-30f) }
        findViewById<Button>(R.id.btnRight).setOnClickListener{ gameView.movePlayer(30f) }
        findViewById<Button>(R.id.btnFire).setOnClickListener{ gameView.fireBullet() }
    }
    /**
     * 홈 버튼·알림 등으로 포커스 잃었다가 복귀 시 전체화면 재적용
     * (일부 기기에서 복귀 시 UI 바가 다시 나타나는 현상 방지)
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
    }
    // 앱이 백그라운드로 이동 시 BGM 일시정지 + 게임 루프 중단
    override fun onPause() {
        super.onPause()
        mediaPlayer?.pause()
        if (gameView.isRunning()) gameView.pause()
    }
    // 앱 복귀 시 BGM 재개 (이미 재생 중이면 중복 start() 방지)
    override fun onResume() {
        super.onResume()
        if (mediaPlayer?.isPlaying == false) mediaPlayer?.start()
    }
    // 앱 종료 시 MediaPlayer 리소스 완전 해제 (메모리 누수 방지)
    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        gameView.releaseSounds()
    }
}
//─────────────────────────────────────────────────────────────
// 데이터 클래스 (게임 엔티티)
//─────────────────────────────────────────────────────────────
/** 2D 좌표 기본 단위. 게임 내 모든 객체의 위치를 표현 */
data class Point(var x: Float, var y: Float)
/** 플레이어 기체. 좌우 이동만 허용되며 화면 끝에서 coerceIn으로 범위 제한 */
class Player(var pos: Point)
/** 발사된 총알. 매 프레임 y 좌표 감소(위쪽 이동)하며 화면 상단 벗어나면 제거 */
class Bullet(var pos: Point)
/**
 * 적군 기체
 * @param type 등급 (0=마름모 일반, 1=육각형 중급, 2=X형 보스)
 *
 * 등급별 스펙:
 * type 0 → HP 1, 점수 10점, 항상 등장
 * type 1 → HP 3, 점수 30점, 50점 이상부터 등장
 * type 2 → HP 6, 점수 80점, 100점 이상부터 등장
 */
class Enemy(var pos: Point, var speed: Float, val type: Int = 0) {
    val maxHp: Int = when (type) { 0 -> 1; 1 -> 3; else -> 6 }
    var hp: Int = maxHp
    val scoreValue: Int = when (type) { 0 -> 10; 1 -> 30; else -> 80 }
    var hitFlash: Float = 0f // 피격 시 흰색 번쩍임 강도 (1.0 → 0.0 감소)
}
/** 배경 별 하나. 크기·속도·투명도가 각각 달라 시차(Parallax) 효과 연출 */
data class Star(var x: Float, var y: Float, val size: Float, val speed: Float, val alpha: Int)
/** 폭발·피격 시 방출되는 파티클 하나. 중력(vy 증가)과 수명(life 감소)을 가짐 */
data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val
color: Int)
//─────────────────────────────────────────────────────────────
// SoundSynthesizer: 외부 파일 없이 PCM 데이터를 코드로 직접 생성
//─────────────────────────────────────────────────────────────
/**
 * 수학적 파형 합성으로 효과음을 실시간 생성하는 클래스
 *
 * 모든 효과음은 앱 시작 시 AudioTrack.MODE_STATIC으로 메모리에 미리 로드되며,
 * stop() → reloadStaticData() → play() 패턴으로 딜레이 없이 즉시 재생된다.
 *
 * 사용 사운드:
 * - shootTrack : 발사음 (주파수 스윕 사인파)
 * - explosionTrack: 폭발음 (백색 노이즈 + 지수 감쇄)
 * - hitTrack : 피격음 (저주파 펄스, 격추 전 체력 감소 시)
 * - reloadTrack : 재장전 완료음 (상승 투톤 차임)
 */
class SoundSynthesizer {
    private val sr = 22050 // 샘플레이트 22.05kHz (표준 저지연설정)
    private var shootTrack: AudioTrack? = null
    private var explosionTrack: AudioTrack? = null
    private var hitTrack: AudioTrack? = null
    private var reloadTrack: AudioTrack? = null
    init { createShoot(); createExplosion(); createHit(); createReload() }
    /**
     * 발사음 생성: Frequency Sweep (주파수 하강 사인파)
     * 고음(1500Hz) → 저음(150Hz)으로 빠르게 하강하는 "피융~" 소리
     * 원리: 시간에 따라 위상각을 적분하여 주파수가 선형 변화하도록 계산
     */
    private fun createShoot() {
        val n = (0.15f * sr).toInt() // 0.15초분 샘플 수
        val pcm = ByteArray(2 * n) // 16bit PCM = 샘플당 2바이트
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            // 주파수가 시간에 따라 변하는 위상각 (주파수 스윕 공식)
            val angle = 2 * Math.PI * (1500 * t + 0.5 * (150 - 1500) * t * t / 0.15)
            val v = (sin(angle) * 32767).toInt().toShort() // 16bit 범위로 정규화
            pcm[2*i] = (v.toInt() and 0xff).toByte()
            pcm[2*i+1] = ((v.toInt() shr 8) and 0xff).toByte()
        }
        shootTrack = build(pcm)
    }
    /**
     * 폭발음 생성: White Noise + Exponential Envelope
     * 랜덤 노이즈(지지직)에 지수 감쇄(e^-8t)를 곱해 "쾅!" 후 자연 소멸
     * 폭발음의 핵심은 초반 강한 충격 후 자연스러운 여운
     */
    private fun createExplosion() {
        val n = (0.4f * sr).toInt()
        val pcm = ByteArray(2 * n)
        val rng = Random()
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val envelope = exp(-8.0 * t) // 지수 감쇄: 초반 강하고 뒤로갈수록 약해짐
            val noise = rng.nextDouble() * 2.0 - 1.0 // -1.0 ~ 1.0 무작위 백색 노이즈
            val v = (noise * envelope * 32767).toInt().toShort()
            pcm[2*i] = (v.toInt() and 0xff).toByte()
            pcm[2*i+1] = ((v.toInt() shr 8) and 0xff).toByte()
        }
        explosionTrack = build(pcm)
    }
    /**
     * 피격음 생성: 짧고 둔탁한 저주파 펄스 (220Hz)
     * 격추가 아닌 체력만 깎일 때 사용 → 폭발음과 청각적으로 구분
     */
    private fun createHit() {
        val n = (0.08f * sr).toInt() // 0.08초, 매우 짧음
        val pcm = ByteArray(2 * n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val v = (sin(2 * Math.PI * 220 * t) * exp(-20 * t) * 32767).toInt().toShort()
            pcm[2*i] = (v.toInt() and 0xff).toByte()
            pcm[2*i+1] = ((v.toInt() shr 8) and 0xff).toByte()
        }
        hitTrack = build(pcm)
    }
    /**
     * 재장전 완료음 생성: 상승 투톤 차임 (660Hz → 880Hz)
     * 0.12초 구간에서 주파수를 전환하여 "띵~띵" 완료 신호 표현
     * 각 음은 독립적인 지수 감쇄를 가져 자연스러운 울림 구현
     */
    private fun createReload() {
        val n = (0.25f * sr).toInt()
        val pcm = ByteArray(2 * n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val freq = if (t < 0.12) 660.0 else 880.0 // 구간별 음높이 전환
            val env = if (t < 0.12) exp(-8 * t) else exp(-8 * (t - 0.12)) // 각 음 개별 감쇄
            val v = (sin(2 * Math.PI * freq * t) * env * 28000).toInt().toShort()
            pcm[2*i] = (v.toInt() and 0xff).toByte()
            pcm[2*i+1] = ((v.toInt() shr 8) and 0xff).toByte()
        }
        reloadTrack = build(pcm)
    }
    /**
     * AudioTrack 생성 헬퍼
     * MODE_STATIC: 데이터를 메모리에 통째로 올려두고 즉시 재생 (I/O 지연 없음)
     */
    private fun build(pcm: ByteArray) = AudioTrack(
        AudioManager.STREAM_MUSIC, sr,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        pcm.size,
        AudioTrack.MODE_STATIC
    ).apply { write(pcm, 0, pcm.size) }
    /**
     * 공통 재생 함수
     * stop() → reloadStaticData()로 재생 위치 초기화 후 play()
     * STATE_INITIALIZED 확인으로 미초기화 상태에서의 크래시 방지
     */
    private fun play(t: AudioTrack?) {
        t?.let {
            if (it.state == AudioTrack.STATE_INITIALIZED)
                try { it.stop(); it.reloadStaticData(); it.play() }
                catch (_: Exception) {}
        }
    }
    fun playShoot() { play(shootTrack) }
    fun playExplosion() { play(explosionTrack) }
    fun playHit() { play(hitTrack) }
    fun playReload() { play(reloadTrack) }
    /** 모든 AudioTrack 리소스 해제 (onDestroy 시 호출) */
    fun release() {
        listOf(shootTrack, explosionTrack, hitTrack, reloadTrack).forEach { it?.release() }
        shootTrack = null; explosionTrack = null; hitTrack = null; reloadTrack = null
    }
}
//─────────────────────────────────────────────────────────────
// GameView: 게임의 핵심 렌더링·로직 담당
//─────────────────────────────────────────────────────────────
/**
 * SurfaceView 기반 게임 뷰
 *
 * 구조:
 * - SurfaceHolder 콜백으로 surfaceCreated 시 게임 루프 스레드 시작
 * - run() 루프에서 updateLogic() → drawGame()을 ~60FPS로 반복
 * - 모든 게임 객체는 CopyOnWriteArrayList로 관리해 스레드 충돌 방지
 *
 * 탄약 시스템:
 * - 최대 50발, 소진 시 자동 재장전 (3초)
 * - 재장전 중 발사 불가, 완료 시 차임 효과음 재생
 */
class GameView(context: Context, attrs: android.util.AttributeSet)
    : SurfaceView(context, attrs), Runnable {
    private var gameThread: Thread? = null
    private var running = false
    private val sh = getHolder()
    private val rng = Random()
    // 게임 객체 리스트 (CopyOnWriteArrayList: 읽기 중 수정 안전)
    private val player = Player(Point(500f, 1200f))
    private val bullets = CopyOnWriteArrayList<Bullet>()
    private val enemies = CopyOnWriteArrayList<Enemy>()
    private val particles = CopyOnWriteArrayList<Particle>() // 폭발·피격 파티클
    private val stars = mutableListOf<Star>() // 배경 별 (단일 스레드만 접근)
    private var score = 0
    private var isGameOver = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG) // 안티앨리어싱 활성화로 부드러운 그래픽
    private var engineFlicker = 0f // 엔진 불꽃 애니메이션용 각도(sin 파형 입력)
    // ── 탄약 / 재장전 상수 및 상태 ──────────────────────────
    private val MAX_AMMO = 50 // 최대 탄약 수
    private val RELOAD_TIME_MS = 3000L // 재장전 소요 시간 (3초)
    private var ammo = MAX_AMMO
    private var isReloading = false
    private var reloadStartMs = 0L // 재장전 시작 시각(System.currentTimeMillis)
    private var reloadSoundPlayed = false // 완료음 중복 재생 방지 플래그
    var soundSynthesizer: SoundSynthesizer? = null // MainActivity에서 외부 주입
    init {
        // SurfaceHolder 콜백 등록
        sh.addCallback(object : SurfaceHolder.Callback {
            // Surface 생성 완료 시 게임 루프 시작
            override fun surfaceCreated(h: SurfaceHolder) { resume() }
            // Surface 크기 확정 시 플레이어 초기 위치 설정 + 별 초기화
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h1: Int) {
                player.pos.x = w / 2f // 화면 중앙 하단
                player.pos.y = h1 - 200f
                initStars(w, h1)
            }
            // Surface 소멸 시 게임 루프 중단 (메모리/스레드 누수 방지)
            override fun surfaceDestroyed(h: SurfaceHolder) { pause() }
        })
    }
    /**
     * 배경 별 120개 초기화
     * 크기(0.5~3.5px), 속도(1~4px/frame), 투명도를 무작위로 설정해
     * 가까운 별(크고 빠름)과 먼 별(작고 느림)의 시차 효과 구현
     */
    private fun initStars(w: Int, h: Int) {
        stars.clear()
        repeat(120) {
            stars.add(Star(
                x = rng.nextFloat() * w,
                y = rng.nextFloat() * h,
                size = rng.nextFloat() * 3f + 0.5f,
                speed = rng.nextFloat() * 3f + 1f,
                alpha = rng.nextInt(155) + 100
            ))
        }
    }
    /**
     * 게임 루프 (별도 스레드에서 실행)
     * 1. 로직 업데이트 (updateLogic)
     * 2. 화면 그리기 (drawGame)
     * 3. 1500ms마다 적 생성 (spawnEnemy)
     * Thread.sleep(16) → 약 60FPS 유지
     */
    override fun run() {
        var lastSpawn = System.currentTimeMillis()
        while (running) {
            if (!sh.surface.isValid) continue // Surface 아직 준비 안 됐으면 대 기
            val canvas = sh.lockCanvas() ?: continue // Canvas 잠금 실패 시 스킵
            updateLogic()
            drawGame(canvas)
            sh.unlockCanvasAndPost(canvas) // 그린 내용 화면에 반영
            // 1.5초마다 적군 1기 생성
            if (System.currentTimeMillis() - lastSpawn > 1500) {
                spawnEnemy()
                lastSpawn = System.currentTimeMillis()
            }
            Thread.sleep(16) // ~60FPS
        }
    }
    /**

     * 순서: 재장전 체크 → 별 스크롤 → 파티클 → 총알 → 적군(충돌 포함)
     */
    private fun updateLogic() {
        if (isGameOver) return // 게임 오버 상태면 로직 중단
        // 엔진 불꽃 점멸 각도 증가 (2π 주기 반복)
        engineFlicker = (engineFlicker + 0.3f) % (2 * Math.PI.toFloat())
        // 재장전 완료 체크: 경과 시간이 RELOAD_TIME_MS 이상이면 완료 처리
        if (isReloading) {
            val elapsed = System.currentTimeMillis() - reloadStartMs
            if (elapsed >= RELOAD_TIME_MS) {
                isReloading = false
                ammo = MAX_AMMO
                // 완료음은 딱 한 번만 재생 (루프에서 중복 호출 방지)
                if (!reloadSoundPlayed) {
                    soundSynthesizer?.playReload()
                    reloadSoundPlayed = true
                }
            }
        }
        // 별 아래로 스크롤 → 화면 아래 벗어나면 위로 재배치 (무한 스크롤)
        stars.forEach { s ->
            s.y += s.speed
            if (s.y > height) { s.y = -5f; s.x = rng.nextFloat() * width }
        }
        // 파티클: 위치 이동 + 중력(vy 증가) + 수명 감소 → 수명 0 이하면 제거
        val pIt = particles.iterator()
        while (pIt.hasNext()) {
            val p = pIt.next()
            p.x += p.vx; p.y += p.vy
            p.vy += 0.3f // 중력 가속도
            p.life -= 0.05f
            if (p.life <= 0f) particles.remove(p)
        }
        // 총알: 위로 이동 → 화면 상단 벗어나면 제거
        val bIt = bullets.iterator()
        while (bIt.hasNext()) {
            val b = bIt.next()
            b.pos.y -= 32f
            if (b.pos.y < 0) bullets.remove(b)
        }
        // 적군 이동 + 충돌 감지
        val eIt = enemies.iterator()
        while (eIt.hasNext()) {
            val e = eIt.next()
            e.pos.y += e.speed // 아래로 이동
            if (e.hitFlash > 0f) e.hitFlash -= 0.15f // 피격 번쩍임 서서히 감소
            // 총알과 적군 충돌 감지 (AABB 근사: 좌표 거리 50px 이내)
            val buIt = bullets.iterator()
            while (buIt.hasNext()) {
                val bu = buIt.next()
                if (abs(e.pos.x - bu.pos.x) < 50 && abs(e.pos.y - bu.pos.y) < 50) {
                    bullets.remove(bu) // 총알 제거
                    e.hp-- // 적 HP 감소
                    e.hitFlash = 1f // 번쩍임 시작
                    if (e.hp <= 0) {
                        // HP 소진 → 격추: 점수 획득 + 대형 폭발
                        enemies.remove(e)
                        score += e.scoreValue
                        spawnExplosion(e.pos.x, e.pos.y, e.type)
                        soundSynthesizer?.playExplosion()
                    } else {
                        // HP 남음 → 피격: 소형 파티클 + 피격음
                        spawnHit(e.pos.x, e.pos.y, e.type)
                        soundSynthesizer?.playHit()
                    }
                    break // 하나의 총알로 중복 충돌 방지
                }
            }
            // 플레이어와 적군 충돌 → 게임 오버
            if (abs(e.pos.x - player.pos.x) < 70 && abs(e.pos.y - player.pos.y) < 70) {
                isGameOver = true
                spawnExplosion(player.pos.x, player.pos.y, -1) // 플레이어 폭발 파티클
            }
            // 화면 하단 벗어난 적 제거
            if (e.pos.y > height) enemies.remove(e)
        }
    }
    /**
     * 격추 폭발 파티클 생성 (22개)
     * 타입별로 색상이 다르며, 사방으로 랜덤 속도로 방출
     */
    private fun spawnExplosion(x: Float, y: Float, type: Int) {
        val colors = when (type) {
            0 -> listOf(Color.RED, Color.YELLOW, Color.WHITE, 0xFFFF6600.toInt())
            1 -> listOf(0xFFFF8C00.toInt(), Color.YELLOW, Color.WHITE,
                0xFFFFCC00.toInt())
            2 -> listOf(0xFFCC00FF.toInt(), Color.WHITE, 0xFFFF00FF.toInt(),
                0xFF8800FF.toInt())
            else -> listOf(Color.CYAN, Color.WHITE, 0xFF00AAFF.toInt()) // 플레이어 격추
        }
        repeat(22) {
            val a = rng.nextFloat() * 2 * Math.PI.toFloat() // 랜덤 방향 (0 ~ 2π)
            val s = rng.nextFloat() * 9f + 2f // 랜덤 속도
            particles.add(Particle(x, y, cos(a) * s, sin(a) * s,
                rng.nextFloat() * 0.7f + 0.4f, colors[rng.nextInt(colors.size)]))
        }
    }
    /**
     * 피격(체력 감소) 파티클 생성 (6개, 소규모)
     * 격추 폭발보다 적은 수로 "맞았다" 느낌만 전달
     */
    private fun spawnHit(x: Float, y: Float, type: Int) {
        val c = when (type) { 0 -> Color.RED; 1 -> 0xFFFF8C00.toInt(); else ->
            0xFFCC00FF.toInt() }
        repeat(6) {
            val a = rng.nextFloat() * 2 * Math.PI.toFloat()
            val s = rng.nextFloat() * 4f + 1f
            particles.add(Particle(x, y, cos(a) * s, sin(a) * s,
                rng.nextFloat() * 0.3f + 0.2f, c))
        }
    }
    // ─────────────────────────────────────────────────────────
    // 그리기 함수들
    // ─────────────────────────────────────────────────────────
    /**
     * 메인 드로잉 함수 (매 프레임 호출)
     * 렌더링 순서: 배경 → 별 → HUD(점수/탄약) → 총알 → 적군 → 파티클 → 플레
    이어
     * (나중에 그릴수록 위에 표시됨)
     */
    private fun drawGame(canvas: Canvas) {
        // 우주 배경: 위(짙은 검정-파랑) → 아래(짙은 남색) 그라데이션
        val bg = Paint()
        bg.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            Color.rgb(5, 8, 25), Color.rgb(15, 23, 60), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        // 별 그리기 (각각 다른 투명도·크기)
        stars.forEach { s ->
            paint.color = Color.WHITE
            paint.alpha = s.alpha
            canvas.drawCircle(s.x, s.y, s.size, paint)
        }
        paint.alpha = 255 // 이후 그리기를 위해 alpha 복원
        // 점수 텍스트 (그림자 → 본문 순으로 그려 입체감)
        paint.style = Paint.Style.FILL
        paint.textSize = 58f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = Color.argb(120, 0, 200, 255) // 반투명 청색 그림자
        canvas.drawText("Score: $score", 52f, 102f, paint)
        paint.color = Color.WHITE
        canvas.drawText("Score: $score", 50f, 100f, paint)
        // 게임 오버 상태: 오버레이 + 파티클만 표시 후 종료
        if (isGameOver) {
            drawGameOver(canvas)
            drawParticles(canvas)
            return
        }
        // 일반 게임 중: 게임 오브젝트 순서대로 렌더링
        bullets.forEach { drawBullet(canvas, it) }
        enemies.forEach { drawEnemy(canvas, it) }
        drawParticles(canvas)
        drawPlayer(canvas)
        drawAmmoUI(canvas) // 탄약 UI는 최상단에 표시
    }
    /**
     * 탄약 UI 드로잉 (우측 상단)
     *
     * 일반 상태: AMMO 라벨 + 탄약 수 + 컬러 진행 바 (파랑→노랑→빨강)
     * 재장전 중: RELOADING 점멸 텍스트 + 노란 진행 바 + 남은 시간(초)
     */
    private fun drawAmmoUI(canvas: Canvas) {
        val uiX = width - 260f // UI 좌측 시작 X
        val uiY = 60f // UI 상단 Y
        val barW = 220f // 진행 바 너비
        val barH = 22f // 진행 바 높이
        if (isReloading) {
            // 재장전 진행률 계산 (0.0 ~ 1.0)
            val elapsed = System.currentTimeMillis() - reloadStartMs
            val progress = (elapsed.toFloat() / RELOAD_TIME_MS).coerceIn(0f, 1f)
            // 배경 패널
            paint.color = Color.argb(160, 10, 10, 30)
            canvas.drawRoundRect(uiX-10f, uiY-28f, uiX+barW+10f, uiY+barH+30f, 10f,
                10f, paint)
            // "RELOADING..." 점멸: 400ms 주기로 on/off
            val blink = (System.currentTimeMillis() / 400) % 2 == 0L
            paint.textSize = 22f; paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            paint.color = if (blink) Color.rgb(255, 220, 0) else Color.argb(140, 255, 220,
                0)
            canvas.drawText("RELOADING...", uiX + barW / 2f, uiY - 4f, paint)
            // 재장전 진행 바 배경
            paint.color = Color.argb(180, 40, 40, 40)
            canvas.drawRoundRect(uiX, uiY+2f, uiX+barW, uiY+barH+2f, 5f, 5f, paint)
            // 재장전 진행 바 (노란 그라데이션, progress 비율만큼 채움)
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            barPaint.shader = LinearGradient(uiX, 0f, uiX+barW*progress, 0f,
                Color.rgb(255, 180, 0), Color.rgb(255, 240, 80), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(uiX, uiY+2f, uiX+barW*progress, uiY+barH+2f, 5f, 5f,
                barPaint)
            // 남은 시간 텍스트 (소수점 1자리)
            val remaining = (RELOAD_TIME_MS - elapsed) / 1000f
            paint.textSize = 18f; paint.color = Color.WHITE
            canvas.drawText("${String.format("%.1f", remaining)}s", uiX+barW/2f,
                uiY+barH+22f, paint)
            // 진행 바 테두리
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
            paint.color = Color.argb(200, 255, 200, 0)
            canvas.drawRoundRect(uiX, uiY+2f, uiX+barW, uiY+barH+2f, 5f, 5f, paint)
            paint.style = Paint.Style.FILL; paint.strokeWidth = 1f
        } else {
            // 배경 패널
            paint.color = Color.argb(140, 10, 10, 30)
            canvas.drawRoundRect(uiX-10f, uiY-28f, uiX+barW+10f, uiY+barH+14f, 10f,
                10f, paint)
            // AMMO 라벨
            paint.textSize = 22f; paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.LEFT
            paint.color = Color.rgb(100, 200, 255)
            canvas.drawText("AMMO", uiX, uiY - 4f, paint)
            // 탄약 수 (잔량에 따라 색상 변경: 충분→흰색, 절반→노랑, 부족→빨강)
            paint.textAlign = Paint.Align.RIGHT
            paint.color = when {
                ammo > MAX_AMMO * 0.5f -> Color.WHITE
                ammo > MAX_AMMO * 0.2f -> Color.rgb(255, 200, 0)
                else -> Color.rgb(255, 80, 80)
            }
            canvas.drawText("$ammo / $MAX_AMMO", uiX + barW, uiY - 4f, paint)
            paint.textAlign = Paint.Align.LEFT
            // 탄약 바 배경
            paint.color = Color.argb(180, 40, 40, 40)
            canvas.drawRoundRect(uiX, uiY+2f, uiX+barW, uiY+barH+2f, 5f, 5f, paint)
            // 탄약 바 본체 (잔량 비율만큼 채움, 그라데이션)
            val ratio = ammo.toFloat() / MAX_AMMO
            val c1 = when { ratio>0.5f->Color.rgb(0,160,255); ratio>0.2f->Color.rgb(255,180,0); else->Color.rgb(255,60,60) }
            val c2 = when { ratio>0.5f->Color.rgb(80,220,255); ratio>0.2f->Color.rgb(255,240,80); else->Color.rgb(255,140,80) }
            val ammoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            ammoPaint.shader = LinearGradient(uiX, 0f, uiX+barW*ratio, 0f, c1, c2,
                Shader.TileMode.CLAMP)
            canvas.drawRoundRect(uiX, uiY+2f, uiX+barW*ratio, uiY+barH+2f, 5f, 5f,
                ammoPaint)
            // 10발마다 눈금선 (가독성 향상)
            paint.color = Color.argb(80, 255, 255, 255)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
            for (i in 1 until MAX_AMMO / 10) {
                val lx = uiX + barW * (i * 10f / MAX_AMMO)
                canvas.drawLine(lx, uiY+2f, lx, uiY+barH+2f, paint)
            }
            paint.style = Paint.Style.FILL; paint.strokeWidth = 1f
            // 탄약 바 테두리
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
            paint.color = Color.argb(160, 100, 180, 255)
            canvas.drawRoundRect(uiX, uiY+2f, uiX+barW, uiY+barH+2f, 5f, 5f, paint)
            paint.style = Paint.Style.FILL; paint.strokeWidth = 1f
        }
        paint.textAlign = Paint.Align.LEFT
    }
    /**
     * 플레이어 우주선 드로잉
     * 구성: 엔진 불꽃(3개, 점멸) + 선체(삼각형) + 날개(좌우) + 조종석(타원)
     * LinearGradient/RadialGradient로 입체감 표현
     */
    private fun drawPlayer(canvas: Canvas) {
        val x = player.pos.x; val y = player.pos.y
        // 엔진 불꽃: sin 파형으로 크기 점멸 (28~38px 범위)
        val flameLen = 28f + sin(engineFlicker) * 10f
        val fp = Paint(Paint.ANTI_ALIAS_FLAG)
        // 메인 엔진 (중앙)
        fp.shader = RadialGradient(x, y+45f, flameLen, Color.WHITE,
            Color.TRANSPARENT, Shader.TileMode.CLAMP)
        fp.alpha = (180 + sin(engineFlicker) * 60).toInt().coerceIn(0, 255)
        canvas.drawOval(x-14f, y+30f, x+14f, y+30f+flameLen, fp)
        // 보조 엔진 (좌우, 크기 60% + 더 투명)
        fp.alpha = (120 + sin(engineFlicker) * 40).toInt().coerceIn(0, 255)
        canvas.drawOval(x-32f, y+10f, x-18f, y+10f+flameLen*0.6f, fp)
        canvas.drawOval(x+18f, y+10f, x+32f, y+10f+flameLen*0.6f, fp)
        // 선체 (오각형 삼각형 + 내부 들여쓰기)
        val body = Path().apply {
            moveTo(x, y-48f) // 기수
            lineTo(x-28f, y+30f) // 좌측 하단
            lineTo(x-12f, y+18f) // 내부 좌 (들여쓰기)
            lineTo(x+12f, y+18f) // 내부 우
            lineTo(x+28f, y+30f) // 우측 하단
            close()
        }
        paint.shader = LinearGradient(x-28f, 0f, x+28f, 0f,
            Color.rgb(0,180,255), Color.rgb(0,100,200), Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        canvas.drawPath(body, paint); paint.shader = null
        // 날개 (좌/우 대칭)
        paint.color = Color.rgb(0, 130, 200)
        canvas.drawPath(Path().apply {
            moveTo(x-28f,y+30f); lineTo(x-55f,y+38f); lineTo(x-38f,y+10f); lineTo(x-20f,y+10f); close() }, paint)
        canvas.drawPath(Path().apply {
            moveTo(x+28f,y+30f); lineTo(x+55f,y+38f); lineTo(x+38f,y+10f);
            lineTo(x+20f,y+10f); close() }, paint)
        // 조종석 (RadialGradient로 유리 반사 느낌)
        paint.shader = RadialGradient(x-5f, y-15f, 14f,
            Color.rgb(180,240,255), Color.rgb(0,120,200), Shader.TileMode.CLAMP)
        canvas.drawOval(x-11f, y-30f, x+11f, y-2f, paint); paint.shader = null
        // 선체 테두리 (반투명 청색)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
        paint.color = Color.argb(180, 100, 220, 255)
        canvas.drawPath(body, paint)
        paint.style = Paint.Style.FILL; paint.strokeWidth = 1f
    }
    /**
     * 총알 드로잉
     * 구성: 꼬리 광선(LinearGradient) + 글로우(RadialGradient) + 본체(흰 원)
     */
    private fun drawBullet(canvas: Canvas, b: Bullet) {
        // 꼬리 광선 (아래 투명 → 위 노란색)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.shader = LinearGradient(b.pos.x, b.pos.y+30f, b.pos.x, b.pos.y-10f,
            Color.TRANSPARENT, Color.argb(180, 255, 255, 100),
            Shader.TileMode.CLAMP)
        canvas.drawRect(b.pos.x-4f, b.pos.y, b.pos.x+4f, b.pos.y+30f, tp)
        // 외곽 글로우 (방사형 노란색 빛)
        val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        gp.shader = RadialGradient(b.pos.x, b.pos.y, 12f,
            Color.argb(120, 255, 255, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(b.pos.x, b.pos.y, 12f, gp)
        // 본체 (흰색 원)
        paint.color = Color.WHITE
        canvas.drawCircle(b.pos.x, b.pos.y, 5f, paint)
    }
    /**
     * 적군 드로잉
     * 타입별 모양: 0=마름모, 1=육각형, 2=X형
     * 공통: 글로우 → 본체(그라데이션) → 피격 번쩍임 → 테두리 → 체력바
     */
    private fun drawEnemy(canvas: Canvas, e: Enemy) {
        val x = e.pos.x; val y = e.pos.y
        // 외곽 글로우 (타입별 색상)
        val glowColor = when (e.type) {
            0 -> Color.argb(60, 255, 50, 50)
            1 -> Color.argb(60, 255, 150, 0)
            else -> Color.argb(60, 200, 0, 255)
        }
        val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        gp.shader = RadialGradient(x, y, 50f, glowColor, Color.TRANSPARENT,
            Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, 50f, gp)
        val bc = when (e.type) { 0->Color.rgb(220,50,50); 1->Color.rgb(255,140,0); else->Color.rgb(180,0,220) }
        val dc = when (e.type) { 0->Color.rgb(140,20,20); 1->Color.rgb(180,80,0); else->Color.rgb(100,0,160) }
        paint.style = Paint.Style.FILL
        val path = Path()
        when (e.type) {
            0 -> { // 마름모: 상하좌우 4꼭짓점
                path.moveTo(x, y-35f); path.lineTo(x+28f, y)
                path.lineTo(x, y+35f); path.lineTo(x-28f, y); path.close()
                paint.shader = LinearGradient(x-28f, y, x+28f, y, bc, dc,
                    Shader.TileMode.CLAMP)
                canvas.drawPath(path, paint); paint.shader = null
                paint.color = Color.argb(200, 255, 100, 100)
                canvas.drawCircle(x, y, 8f, paint) // 코어
            }
            1 -> { // 육각형: 6꼭짓점 균등 배치 (30° 오프셋)
                for (i in 0 until 6) {
                    val a = Math.PI / 3.0 * i - Math.PI / 6.0
                    val px = x + (32f * cos(a)).toFloat(); val py = y + (32f *
                            sin(a)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                paint.shader = LinearGradient(x, y-32f, x, y+32f, bc, dc,
                    Shader.TileMode.CLAMP)
                canvas.drawPath(path, paint); paint.shader = null
                // 내부 링 (에너지 코어 느낌)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                paint.color = Color.argb(180, 255, 200, 50)
                canvas.drawCircle(x, y, 16f, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(200, 255, 180, 0)
                canvas.drawCircle(x, y, 7f, paint)
            }
            else -> { // X형: 4방향 팔 + 중앙 코어
                listOf(-1f, 1f).forEach { sx -> listOf(-1f, 1f).forEach { sy ->
                    canvas.drawPath(Path().apply {
                        moveTo(x, y); lineTo(x+sx*12f, y+sy*12f)
                        lineTo(x+sx*38f, y+sy*22f); lineTo(x+sx*30f, y+sy*30f)
                        lineTo(x+sx*14f, y+sy*14f); close()
                    }.also { paint.color = bc }, paint)
                }}
                paint.color = dc; canvas.drawCircle(x, y, 14f, paint)
                paint.color = Color.argb(220, 230, 50, 255); canvas.drawCircle(x, y, 7f,
                    paint)
            }
        }
        // 피격 번쩍임: hitFlash가 남아있으면 흰색 반투명 오버레이
        if (e.hitFlash > 0f) {
            paint.color = Color.argb((e.hitFlash * 200).toInt().coerceIn(0, 200), 255, 255,
                255)
            canvas.drawPath(path, paint)
        }
        // 테두리
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
        paint.color = Color.argb(160, 255, 200, 200)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL; paint.strokeWidth = 1f
        // 체력바: HP가 1 초과인 적만 표시 (마름모는 1발에 격추되므로 불필요)
        if (e.maxHp > 1) {
            val bw = 60f; val bh = 7f
            val bx = x - bw / 2f; val by = y - 52f
            val ratio = e.hp.toFloat() / e.maxHp
            // 체력바 배경
            paint.color = Color.argb(180, 40, 40, 40)
            canvas.drawRoundRect(bx, by, bx+bw, by+bh, 3f, 3f, paint)
            // 체력바 본체 (비율에 따라 초록→노랑→빨강)
            paint.color = when {
                ratio > 0.6f -> Color.rgb(50, 220, 80)
                ratio > 0.3f -> Color.rgb(255, 200, 0)
                else -> Color.rgb(255, 60, 60)
            }
            canvas.drawRoundRect(bx, by, bx+bw*ratio, by+bh, 3f, 3f, paint)
            // 체력바 테두리
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
            paint.color = Color.argb(200, 200, 200, 200)
            canvas.drawRoundRect(bx, by, bx+bw, by+bh, 3f, 3f, paint)
            paint.style = Paint.Style.FILL
            // 체력 수치 텍스트 (e.g. "3/3")
            paint.textSize = 18f; paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            paint.color = Color.WHITE
            canvas.drawText("${e.hp}/${e.maxHp}", x, by - 2f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }
    /** 파티클 드로잉: 수명(life)에 비례한 크기·투명도로 자연스러운 소멸 표현 */
    private fun drawParticles(canvas: Canvas) {
        particles.forEach { p ->
            paint.color = p.color
            paint.alpha = (p.life * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, p.life * 7f, paint)
        }
        paint.alpha = 255 // 이후 그리기를 위해 alpha 복원
    }
    /** 게임 오버 화면: 반투명 오버레이 + GAME OVER 텍스트 + 점수 */
    private fun drawGameOver(canvas: Canvas) {
        // 어두운 반투명 오버레이
        paint.color = Color.argb(160, 0, 0, 20)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        // GAME OVER (그림자 + 본문)
        paint.textSize = 96f
        paint.color = Color.argb(80, 255, 50, 50)
        canvas.drawText("GAME OVER", width/2f+3f, height/2f-57f, paint)
        paint.color = Color.rgb(255, 80, 80)
        canvas.drawText("GAME OVER", width/2f, height/2f-60f, paint)
        paint.textSize = 46f; paint.color = Color.argb(200, 200, 220, 255)
        canvas.drawText("FIRE 버튼으로 재시작", width/2f, height/2f+20f, paint)
        paint.textSize = 52f; paint.color = Color.WHITE
        canvas.drawText("Score: $score", width/2f, height/2f+100f, paint)
        paint.textAlign = Paint.Align.LEFT
    }
    //─────────────────────────────────────────────────────────
    // 공개 인터페이스 (MainActivity에서 호출)
    //─────────────────────────────────────────────────────────
    /** 플레이어 이동: distance만큼 이동, 화면 좌우 끝에서 coerceIn으로 범위 제한 */
    fun movePlayer(d: Float) {
        player.pos.x = (player.pos.x + d).coerceIn(60f, width - 60f)
    }
    /**
     * 발사 버튼 처리
     * - 게임 오버 상태: 모든 상태 초기화 후 재시작
     * - 재장전 중: 발사 불가 (무시)
     * - 정상: 탄약 1발 소모 후 총알 생성, 탄약 0이면 재장전 시작
     */
    fun fireBullet() {
        if (isGameOver) {
            // 재시작: 모든 상태 초기화
            score = 0; isGameOver = false
            ammo = MAX_AMMO; isReloading = false
            enemies.clear(); bullets.clear(); particles.clear()
            return
        }
        if (isReloading) return // 재장전 중 발사 불가
        if (ammo > 0) {
            ammo--
            bullets.add(Bullet(Point(player.pos.x, player.pos.y - 55f)))
            soundSynthesizer?.playShoot()
            // 탄약 소진 → 자동 재장전 시작
            if (ammo == 0) startReload()
        }
    }
    /** 재장전 시작: 타이머 기록 + 플래그 설정 */
    private fun startReload() {
        isReloading = true
        reloadStartMs = System.currentTimeMillis()
        reloadSoundPlayed = false // 완료음 재생 가능 상태로 초기화
    }
    /**
     * 적군 생성
     * - X 위치: 화면 너비 내 랜덤
     * - 속도: 기본 8px/frame + 점수/60 (점수 비례 가속)
     * - 타입: 점수 구간에 따라 등급 상한 결정 후 무작위 선택
     */
    private fun spawnEnemy() {
        if (width == 0) return
        val rx = rng.nextFloat() * (width - 120) + 60
        val speed = 8f + (score / 60f)
        val type = when {
            score >= 100 -> rng.nextInt(3) // 0, 1, 2 모두 등장
            score >= 50 -> rng.nextInt(2) // 0, 1만 등장
            else -> 0 // 마름모만 등장
        }
        enemies.add(Enemy(Point(rx, -60f), speed, type))
    }
    /** 게임 루프 실행 여부 반환 (MainActivity에서 중복 resume 방지에 활용) */
    fun isRunning() = running
    /** 게임 루프 시작 (이미 실행 중이면 무시) */
    fun resume() {
        if (running) return
        running = true
        gameThread = Thread(this)
        gameThread?.start()
    }
    /** 게임 루프 중단 (join으로 스레드 종료 대기, 최대 500ms) */
    fun pause() {
        running = false
        try { gameThread?.join(500) }
        catch (e: InterruptedException) { e.printStackTrace() }
        gameThread = null
    }
    /** 효과음 리소스 해제 (onDestroy 시 호출) */
    fun releaseSounds() {
        soundSynthesizer?.release()
        soundSynthesizer = null
    }
}