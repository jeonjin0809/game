package com.damum.daycaretemi.play;

import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.damum.daycaretemi.DaycareApp;
import com.damum.daycaretemi.R;
import com.damum.daycaretemi.robot.RobotController;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import org.json.JSONObject;

/** Integrated AI play: four short missions in one session, safe offline fallback. */
public final class MemoryGameActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable countdown = new Runnable() {
        @Override public void run() {
            if (game.phase() != MemoryGame.Phase.MEMORIZE) return;
            long left = Math.max(0, showUntil - SystemClock.elapsedRealtime());
            countdownText.setText("기억할 시간  " + ((left + 999) / 1000) + "초");
            if (left == 0) {
                game.revealAnswerStage(SystemClock.elapsedRealtime());
                showAnswer();
            } else main.postDelayed(this, Math.min(250, left));
        }
    };

    private final Runnable waitingSupport = () -> {
        if (game == null || game.phase() != MemoryGame.Phase.ANSWER) return;
        if (game.hintsInRound() > 0 || game.examplesInRound() > 0) return;
        if (!game.markWaitingSupport()) return;
        String reminder = PlaySupportCoach.waitingMessage();
        feedbackText.setText(reminder);
        feedbackText.setVisibility(View.VISIBLE);
        speak(reminder);
    };

    private String pendingIntroSpeech;
    private String pendingQuestionSpeech;
    private String pendingFeedbackSpeech;
    private boolean previewTimerRunning;
    /** Different independent gates: children should hear feedback and receive the next plan. */
    private boolean feedbackReady;
    private boolean planReady;
    private final Runnable introFallback = () -> {
        // Avoid narration overlapping with the preview if the SDK never sends TTS completion.
        if (pendingIntroSpeech != null && robot != null && robot.isReady()) {
            try { robot.cancelSpeech(); } catch (RuntimeException ignored) { }
        }
        startPreviewTimer();
    };
    private final Runnable questionSpeechFallback = this::finishQuestionSpeech;
    private final Runnable feedbackSpeechFallback = this::finishFeedbackSpeech;

    private RobotController robot;
    private MemoryGame game;
    private AgentMemoryPlanner planner;
    private MemoryAgentClient agent;
    private TextView agentStatus;
    private int requestToken;
    public static final String EXTRA_FOLLOW_UP_MISSION = "follow_up_mission";
    public static final String EXTRA_FOLLOW_UP_CATEGORY = "follow_up_category";
    public static final String EXTRA_FOLLOW_UP_ITEMS = "follow_up_items";
    public static final String EXTRA_FOLLOW_UP_SOURCE_SESSION = "follow_up_from_session";
    private FollowUpRecommendation.Plan approvedFollowUp;
    private String approvedFromSession;
    private String savedSessionId;
    private TextView titleText, progressText, instructionsText, countdownText, feedbackText, connectionText;
    private LinearLayout cardsArea, optionsArea;
    private ScrollView gameScroll;
    private Button startButton, hintButton, exampleButton, nextButton, quitButton;
    private long showUntil;
    private boolean runningSession;
    private final ArrayList<String> storySelected = new ArrayList<>();
    private final RobotController.Listener statusListener = new RobotController.Listener() {
        @Override public void onReadyChanged(boolean ready) {
            main.post(() -> connectionText.setText(ready ? "● temi 연결됨 · 안내 음성 사용"
                    : "○ 연결 대기 중 · 화면 터치 게임은 사용 가능"));
        }
        @Override public void onSpeechStatus(String spoken, RobotController.SpeechStatus status) {
            main.post(() -> {
                if (status != RobotController.SpeechStatus.COMPLETED
                        && status != RobotController.SpeechStatus.ERROR
                        && status != RobotController.SpeechStatus.CANCELED) return;
                if (pendingIntroSpeech != null && pendingIntroSpeech.equals(spoken)) {
                    // If canceled by someone else, still start within the fallback path.
                    if (status != RobotController.SpeechStatus.CANCELED) startPreviewTimer();
                } else if (pendingQuestionSpeech != null && pendingQuestionSpeech.equals(spoken)) {
                    finishQuestionSpeech();
                } else if (pendingFeedbackSpeech != null && pendingFeedbackSpeech.equals(spoken)) {
                    finishFeedbackSpeech();
                }
            });
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_memory_game);
        robot = DaycareApp.robot(this);
        String missionId = getIntent().getStringExtra(EXTRA_FOLLOW_UP_MISSION);
        if (missionId != null) {
            try {
                FollowUpRecommendation.Plan candidate = new FollowUpRecommendation.Plan(
                        missionId, getIntent().getStringExtra(EXTRA_FOLLOW_UP_CATEGORY),
                        getIntent().getIntExtra(EXTRA_FOLLOW_UP_ITEMS, -1), "teacher");
                if (candidate.items < 2 || candidate.items > 3
                        || !("animals".equals(candidate.category) || "fruits".equals(candidate.category)))
                    throw new IllegalArgumentException("Invalid plan");
                MemoryGame.Mission.fromId(candidate.mission);
                approvedFollowUp = candidate;
                approvedFromSession = getIntent().getStringExtra(EXTRA_FOLLOW_UP_SOURCE_SESSION);
            } catch (RuntimeException ignored) { approvedFollowUp = null; }
        }
        planner = makePlanner();
        game = new MemoryGame(planner);
        agent = new MemoryAgentClient();
        titleText = findViewById(R.id.memory_title);
        progressText = findViewById(R.id.memory_progress);
        instructionsText = findViewById(R.id.memory_instructions);
        countdownText = findViewById(R.id.memory_countdown);
        feedbackText = findViewById(R.id.memory_feedback);
        connectionText = findViewById(R.id.memory_connection);
        agentStatus = findViewById(R.id.memory_agent_status);
        gameScroll = findViewById(R.id.memory_scroll);
        cardsArea = findViewById(R.id.memory_cards);
        optionsArea = findViewById(R.id.memory_options);
        startButton = findViewById(R.id.memory_start);
        hintButton = findViewById(R.id.memory_hint);
        exampleButton = findViewById(R.id.memory_example);
        nextButton = findViewById(R.id.memory_next);
        quitButton = findViewById(R.id.memory_quit);

        startButton.setOnClickListener(v -> startGame());
        hintButton.setOnClickListener(v -> requestHint());
        exampleButton.setOnClickListener(v -> requestExample());
        nextButton.setOnClickListener(v -> {
            if (game.phase() == MemoryGame.Phase.ANSWER
                    && game.question().mission == MemoryGame.Mission.STORY)
                resetStorySelection();
            else next();
        });
        quitButton.setOnClickListener(v -> confirmQuit());
        showReady(false);
    }

    @Override protected void onStart() {
        super.onStart();
        robot.addListener(statusListener);
        connectionText.setText(robot.isReady()
                ? "● temi 연결됨 · 안내 음성 사용" : "○ 연결 대기 중 · 화면 터치 게임은 사용 가능");
    }

    @Override protected void onStop() {
        requestToken++;
        main.removeCallbacks(countdown);
        main.removeCallbacks(waitingSupport);
        main.removeCallbacks(introFallback);
        main.removeCallbacks(questionSpeechFallback);
        main.removeCallbacks(feedbackSpeechFallback);
        pendingIntroSpeech = null;
        pendingQuestionSpeech = null;
        pendingFeedbackSpeech = null;
        previewTimerRunning = false;
        robot.removeListener(statusListener);
        try { robot.cancelSpeech(); } catch (RuntimeException ignored) { }
        if (runningSession && game.phase() != MemoryGame.Phase.FINISHED) {
            // Avoid returning to a stale timed question after the app was backgrounded.
            runningSession = false;
            planner = makePlanner();
            game = new MemoryGame(planner);
            showReady(true);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }

    @Override protected void onDestroy() {
        agent.close();
        super.onDestroy();
    }

    private void startGame() {
        requestToken++;
        main.removeCallbacks(countdown);
        main.removeCallbacks(waitingSupport);
        main.removeCallbacks(introFallback);
        main.removeCallbacks(questionSpeechFallback);
        main.removeCallbacks(feedbackSpeechFallback);
        pendingIntroSpeech = null;
        pendingQuestionSpeech = null;
        pendingFeedbackSpeech = null;
        previewTimerRunning = false;
        planner = makePlanner();
        game = new MemoryGame(planner);
        savedSessionId = null;
        // Learning activity never moves temi. Stop any movement left from another screen.
        if (robot.isReady()) {
            try { robot.stopMovement(); }
            catch (RuntimeException ignored) { /* Still playable on-screen without a robot. */ }
        }
        game.start(SystemClock.elapsedRealtime());
        agentStatus.setText(AgentSettings.getUrl(this).isEmpty()
                ? "오프라인 규칙 진행 · AI 서버 미설정"
                : "AI 서버 설정됨 · 이후 문제는 AI 결정 요청");
        runningSession = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!robot.isReady()) Toast.makeText(this, "로봇 음성 없이 화면으로 게임을 진행합니다.", Toast.LENGTH_LONG).show();
        showMemorize();
    }

    private void showReady(boolean interrupted) {
        titleText.setText("AI 기억·이야기 놀이");
        agentStatus.setText(AgentSettings.getUrl(this).isEmpty()
                ? "AI 서버 미설정 · 오프라인 게임 가능"
                : "AI 서버 설정됨 · 연결은 시작 후 확인");
        progressText.setText(approvedFollowUp == null
                ? "5개의 놀이 미션 · 그림, 위치, 사라진 물건, 이야기"
                : "교사가 승인한 후속 놀이 · " + FollowUpRecommendation.title(approvedFollowUp.mission)
                    + "부터 시작 · 마지막에 다시 연습");
        instructionsText.setText(interrupted
                ? "화면을 벗어나 진행 중이던 게임을 초기화했어요. 다시 시작해 주세요."
                : "그림을 보고 기억한 다음, 여러 가지 미션을 해결해 봐!");
        countdownText.setText("준비됐나요?");
        feedbackText.setVisibility(View.GONE);
        cardsArea.removeAllViews();
        optionsArea.removeAllViews();
        startButton.setText("놀이 시작");
        startButton.setVisibility(View.VISIBLE);
        hintButton.setVisibility(View.GONE);
        exampleButton.setVisibility(View.GONE);
        nextButton.setVisibility(View.GONE);
        gameScroll.post(() -> gameScroll.scrollTo(0, 0));
    }

    private void showMemorize() {
        MemoryGame.Question q = game.question();
        storySelected.clear();
        main.removeCallbacks(waitingSupport);
        main.removeCallbacks(introFallback);
        main.removeCallbacks(questionSpeechFallback);
        main.removeCallbacks(feedbackSpeechFallback);
        pendingIntroSpeech = null;
        pendingQuestionSpeech = null;
        pendingFeedbackSpeech = null;
        previewTimerRunning = false;
        titleText.setText(q.mission.title);
        progressText.setText(String.format(Locale.KOREA, "%d / %d 미션 · 그림 %d개",
                game.currentRound(), MemoryGame.TOTAL_ROUNDS, q.sequence.size()));
        String intro;
        if (q.mission == MemoryGame.Mission.STORY)
            intro = "그림 이야기의 처음부터 끝까지 차례를 살펴봐요.";
        else if (q.mission == MemoryGame.Mission.MISSING)
            intro = "어떤 그림이 사라지는지 모두 잘 살펴봐요.";
        else if (q.mission == MemoryGame.Mission.POSITION)
            intro = "그림이 놓인 위치를 기억해 봐요.";
        else intro = "그림을 왼쪽부터 순서대로 기억해 봐요.";
        instructionsText.setText(intro);
        feedbackText.setVisibility(View.GONE);
        startButton.setVisibility(View.GONE);
        nextButton.setVisibility(View.GONE);
        hintButton.setVisibility(View.GONE);
        exampleButton.setVisibility(View.GONE);
        optionsArea.removeAllViews();
        if (q.mission == MemoryGame.Mission.POSITION) showPositionGrid(q, false);
        else showCards(q.sequence, -1);
        gameScroll.post(() -> gameScroll.smoothScrollTo(0, 0));
        String spokenIntro = PlaySupportCoach.introduction(planner.activeSupportStyle(),
                game.currentRound()) + " " + intro;
        if (robot.isReady()) {
            // A preschooler gets the full viewing time AFTER the introduction finishes.
            pendingIntroSpeech = spokenIntro;
            countdownText.setText("설명을 듣고 그림을 살펴봐요");
            main.postDelayed(introFallback, 20_000L);
            speak(spokenIntro);
        } else {
            startPreviewTimer();
        }
    }

    private void startPreviewTimer() {
        if (previewTimerRunning || game.phase() != MemoryGame.Phase.MEMORIZE) return;
        previewTimerRunning = true;
        pendingIntroSpeech = null;
        main.removeCallbacks(introFallback);
        long seconds = game.question().mission == MemoryGame.Mission.STORY ? 12
                : 9L + (game.question().sequence.size() - 2L) * 2L;
        showUntil = SystemClock.elapsedRealtime() + seconds * 1000L;
        main.removeCallbacks(countdown);
        main.post(countdown);
    }

    private void showAnswer() {
        if (game.phase() != MemoryGame.Phase.ANSWER) return;
        MemoryGame.Question q = game.question();
        titleText.setText(q.mission.title);
        instructionsText.setText(q.prompt());
        countdownText.setText(q.mission == MemoryGame.Mission.STORY
                ? "첫 번째부터 차례대로 눌러 보세요 · 시간제한 없음"
                : "하나를 골라주세요 · 시간제한 없음");
        cardsArea.removeAllViews();
        optionsArea.removeAllViews();
        storySelected.clear();
        if (q.mission == MemoryGame.Mission.MISSING) {
            showCards(q.sequence, q.askedIndex); // keep positions, replace one picture with ?
        } else if (q.mission == MemoryGame.Mission.POSITION) {
            showPositionGrid(q, true);
        } else if (q.mission == MemoryGame.Mission.STORY) {
            updateStoryProgress();
        }
        for (MemoryGame.Item choice : q.options) {
            Button b = new Button(this);
            b.setText(choice.label);
            if (!applyPicture(b, choice, 52)) b.setText(choice.display());
            b.setTextSize(q.mission == MemoryGame.Mission.STORY ? 17 : 20);
            b.setAllCaps(false);
            b.setContentDescription(choice.label);
            b.setTag(choice.id);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(116), 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            optionsArea.addView(b, lp);
            if (q.mission == MemoryGame.Mission.STORY)
                b.setOnClickListener(v -> selectStoryCard(choice));
            else b.setOnClickListener(v -> answer(choice.id));
        }
        hintButton.setVisibility(View.VISIBLE);
        hintButton.setEnabled(true);
        exampleButton.setVisibility(View.VISIBLE);
        exampleButton.setEnabled(true);
        if (q.mission == MemoryGame.Mission.STORY) {
            nextButton.setText("순서 다시 고르기");
            nextButton.setEnabled(true);
            nextButton.setVisibility(View.VISIBLE);
        } else nextButton.setVisibility(View.GONE);
        // Give a young child time to answer AFTER the spoken question, not during it.
        pendingQuestionSpeech = q.prompt();
        speak(q.prompt());
        main.removeCallbacks(waitingSupport);
        if (robot.isReady()) {
            main.removeCallbacks(questionSpeechFallback);
            main.postDelayed(questionSpeechFallback, 15_000L);
        } else finishQuestionSpeech();
        gameScroll.post(() -> gameScroll.smoothScrollTo(0,
                Math.max(0, optionsArea.getTop() - dp(240))));
    }

    private void finishQuestionSpeech() {
        if (pendingQuestionSpeech == null) return;
        pendingQuestionSpeech = null;
        main.removeCallbacks(questionSpeechFallback);
        if (game.phase() == MemoryGame.Phase.ANSWER) {
            main.removeCallbacks(waitingSupport);
            main.postDelayed(waitingSupport, 30_000L);
        }
    }

    private void finishFeedbackSpeech() {
        if (pendingFeedbackSpeech == null) return;
        pendingFeedbackSpeech = null;
        main.removeCallbacks(feedbackSpeechFallback);
        feedbackReady = true;
        refreshNextEnabled();
    }

    private void refreshNextEnabled() {
        if (game.phase() == MemoryGame.Phase.FEEDBACK)
            nextButton.setEnabled(feedbackReady && planReady);
    }

    private void showCards(List<MemoryGame.Item> items, int hiddenIndex) {
        cardsArea.removeAllViews();
        cardsArea.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < items.size(); i++) {
            TextView card = new TextView(this);
            card.setGravity(android.view.Gravity.CENTER);
            if (i == hiddenIndex) {
                card.setText("?\n사라짐");
                card.setContentDescription("사라진 그림");
            } else {
                MemoryGame.Item item = items.get(i);
                card.setText(item.label);
                if (!applyPicture(card, item, 72)) card.setText(item.display());
                card.setContentDescription("기억할 그림 " + item.label);
            }
            card.setTextSize(19);
            card.setTextColor(0xFF17334C);
            card.setBackgroundColor(i % 2 == 0 ? 0xFFE9F5FB : 0xFFFFF0D8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(120), 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            cardsArea.addView(card, lp);
        }
    }

    private void showPositionGrid(MemoryGame.Question q, boolean hidden) {
        cardsArea.removeAllViews();
        cardsArea.setOrientation(LinearLayout.VERTICAL);
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 2; column++) {
                int cell = rowIndex * 2 + column;
                MemoryGame.Item cellItem = null;
                if (!hidden) {
                    for (int i = 0; i < q.positionCells.size(); i++) {
                        if (q.positionCells.get(i) == cell) {
                            cellItem = q.sequence.get(i);
                            break;
                        }
                    }
                }
                TextView block = new TextView(this);
                block.setGravity(android.view.Gravity.CENTER);
                block.setText(hidden ? "?" : (cellItem == null ? "빈 칸" : cellItem.label));
                if (cellItem != null && !applyPicture(block, cellItem, 40))
                    block.setText(cellItem.display());
                block.setContentDescription(hidden ? "기억할 물건이 있었던 칸"
                        : (cellItem == null ? "빈 칸" : "그림 " + cellItem.label));
                block.setTextSize(20);
                block.setTextColor(0xFF17334C);
                block.setBackgroundColor((rowIndex + column) % 2 == 0 ? 0xFFE9F5FB : 0xFFFFF0D8);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(82), 1);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(block, lp);
            }
            cardsArea.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void selectStoryCard(MemoryGame.Item choice) {
        if (game.phase() != MemoryGame.Phase.ANSWER || storySelected.contains(choice.id)) return;
        storySelected.add(choice.id);
        for (int i = 0; i < optionsArea.getChildCount(); i++) {
            View option = optionsArea.getChildAt(i);
            if (choice.id.equals(option.getTag())) {
                option.setEnabled(false);
                option.setAlpha(0.4f);
                break;
            }
        }
        updateStoryProgress();
        if (storySelected.size() == game.question().sequence.size()) {
            answer(String.join("|", storySelected));
        }
    }

    private void resetStorySelection() {
        if (game.phase() != MemoryGame.Phase.ANSWER
                || game.question().mission != MemoryGame.Mission.STORY) return;
        storySelected.clear();
        for (int i = 0; i < optionsArea.getChildCount(); i++) {
            View option = optionsArea.getChildAt(i);
            option.setEnabled(true);
            option.setAlpha(1f);
        }
        updateStoryProgress();
    }

    private void updateStoryProgress() {
        cardsArea.removeAllViews();
        cardsArea.setOrientation(LinearLayout.HORIZONTAL);
        TextView progress = new TextView(this);
        progress.setTextSize(18);
        progress.setTextColor(0xFF17334C);
        progress.setGravity(android.view.Gravity.CENTER);
        StringBuilder text = new StringBuilder("내가 고른 순서: ");
        if (storySelected.isEmpty()) text.append("아직 고르지 않았어요");
        else for (int i = 0; i < storySelected.size(); i++) {
            if (i > 0) text.append(" → ");
            for (MemoryGame.Item card : game.question().sequence)
                if (card.id.equals(storySelected.get(i))) { text.append(card.label); break; }
        }
        progress.setText(text.toString());
        cardsArea.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(70)));
    }

    private void requestHint() {
        String hint = game.requestHint();
        if (hint == null) return;
        main.removeCallbacks(waitingSupport);
        hintButton.setEnabled(false);
        feedbackText.setVisibility(View.VISIBLE);
        if (game.question().mission == MemoryGame.Mission.STORY) {
            // Reset an incorrect partial order, then start with the actual first scene.
            resetStorySelection();
            selectStoryCard(game.question().sequence.get(0));
        } else {
            // Visual hint: eliminate one wrong choice instead of relying on written text.
            for (int i = 0; i < optionsArea.getChildCount(); i++) {
                View option = optionsArea.getChildAt(i);
                if (!game.question().correctId.equals(option.getTag())) {
                    option.setVisibility(View.GONE);
                    break;
                }
            }
        }
        feedbackText.setText(hint);
        if (game.phase() == MemoryGame.Phase.ANSWER)
            speak(PlaySupportCoach.hintLead(game.question().mission) + " " + hint);
    }

    private void requestExample() {
        if (game.phase() != MemoryGame.Phase.ANSWER) return;
        String example = game.requestExample();
        if (example == null) return;
        main.removeCallbacks(waitingSupport);
        exampleButton.setEnabled(false);
        feedbackText.setVisibility(View.VISIBLE);
        feedbackText.setText(example);
        speak(example);
    }

    private void answer(String chosenId) {
        if (game.phase() != MemoryGame.Phase.ANSWER) return; // duplicate tap protection
        main.removeCallbacks(waitingSupport);
        main.removeCallbacks(questionSpeechFallback);
        pendingQuestionSpeech = null;
        MemoryGame.RoundResult result = game.choose(chosenId, SystemClock.elapsedRealtime());
        optionsArea.removeAllViews();
        hintButton.setVisibility(View.GONE);
        exampleButton.setVisibility(View.GONE);
        feedbackText.setVisibility(View.VISIBLE);
        String message = PlaySupportCoach.feedback(result,
                game.results().subList(0, game.results().size() - 1));
        feedbackText.setText(message);
        instructionsText.setText("현재 정답 " + game.correctCount() + " / " + game.results().size() + "개 · "
                + "힌트 " + game.totalHints() + "회 사용");
        countdownText.setText("이 문제는 끝났어요.");
        nextButton.setVisibility(View.VISIBLE);
        nextButton.setText(game.results().size() >= MemoryGame.TOTAL_ROUNDS ? "결과 보기" : "다음 문제");
        feedbackReady = !robot.isReady();
        planReady = game.results().size() >= MemoryGame.TOTAL_ROUNDS;
        refreshNextEnabled();
        pendingFeedbackSpeech = robot.isReady() ? message : null;
        speak(message);
        if (pendingFeedbackSpeech != null) {
            // A lost TTS callback must never trap the child on the feedback screen.
            main.postDelayed(feedbackSpeechFallback, 12_000L);
        } else {
            feedbackReady = true;
            refreshNextEnabled();
        }
        if (game.results().size() < MemoryGame.TOTAL_ROUNDS) requestAgentPlan();
        gameScroll.post(() -> gameScroll.smoothScrollTo(0,
                Math.max(0, feedbackText.getTop() - dp(250))));
    }

    /** Waits for one bounded plan; on failure the offline planner is used automatically. */
    private void requestAgentPlan() {
        String url = AgentSettings.getUrl(this);
        if (url.isEmpty()) {
            agentStatus.setText("오프라인 규칙으로 다음 문제 결정");
            planReady = true;
            refreshNextEnabled();
            return;
        }
        planReady = false;
        refreshNextEnabled();
        agentStatus.setText("다음 놀이 계획을 받아오는 중… (최대 6초)");
        final int token = ++requestToken;
        final int completedRounds = game.results().size();
        // Never block a young child for 8+ seconds on unreliable classroom Wi-Fi.
        main.postDelayed(() -> {
            if (token == requestToken && runningSession
                    && game.phase() == MemoryGame.Phase.FEEDBACK
                    && game.results().size() == completedRounds) {
                requestToken++;
                planReady = true;
                refreshNextEnabled();
                agentStatus.setText("AI 응답 지연 · 오프라인 규칙으로 계속 진행");
            }
        }, 6000);
        agent.plan(url, new ArrayList<>(game.results()), planner.followUpTarget(), (body, failure) -> {
            if (token != requestToken || !runningSession || game.phase() != MemoryGame.Phase.FEEDBACK
                    || game.results().size() != completedRounds) return;
            // Invalidate the delayed timeout after a real server reply.
            requestToken++;
            boolean accepted = false;
            if (body != null) {
                String category = body.optString("category", "");
                String mission = body.optString("mission", "");
                String mode = body.optString("mode", "");
                String reason = body.optString("reason", "");
                accepted = planner.accept(new AgentMemoryPlanner.Decision(
                        category, body.optInt("difficulty", -1), mission, mode, reason,
                        body.optString("support_style", "steady")), game.results());
                if (accepted) {
                    String source = "llm".equals(mode) ? "실제 AI" : "규칙 데모 (AI 아님)";
                    agentStatus.setText(source + "가 다음 놀이·지원 방식 선택: "
                            + MemoryGame.Mission.fromId(mission).title + " · "
                            + body.optInt("difficulty") + "개 · "
                            + styleLabel(body.optString("support_style", "steady")) + ". " + reason);
                }
            }
            if (!accepted) agentStatus.setText("AI 응답 없음/검증 실패 · 오프라인 규칙으로 진행");
            planReady = true;
            refreshNextEnabled();
        });
    }

    private void next() {
        if (game.phase() != MemoryGame.Phase.FEEDBACK) return;
        game.advance(SystemClock.elapsedRealtime());
        if (game.phase() == MemoryGame.Phase.FINISHED) {
            runningSession = false;
            savedSessionId = MemorySessionStore.save(this, game, planner.llmDecisions(),
                    planner.demoDecisions(), approvedFromSession, approvedFollowUp,
                    planner.decisionAudit());
            showFinished();
            requestAgentReport();
        } else showMemorize();
    }

    private void requestAgentReport() {
        String url = AgentSettings.getUrl(this);
        if (url.isEmpty() || savedSessionId == null) {
            agentStatus.setText("교사용 후속 놀이 제안은 오프라인 규칙으로 저장됨");
            return;
        }
        agentStatus.setText("교사용 후속 놀이 제안을 정리 중…");
        final String sessionId = savedSessionId;
        final int token = ++requestToken;
        agent.report(url, new ArrayList<>(game.results()), (body, failure) -> {
            if (token != requestToken || isFinishing() || body == null) {
                if (token == requestToken && !isFinishing())
                    agentStatus.setText("AI 보고서 실패 · 오프라인 교사용 제안 저장됨");
                return;
            }
            String note = body.optString("teacher_note", "");
            String mode = body.optString("mode", "");
            boolean accepted = MemorySessionStore.updateTeacherReport(this, sessionId, note,
                    mode, body.optJSONObject("next_plan"));
            if (accepted) agentStatus.setText("llm".equals(mode)
                    ? "AI의 다음 놀이 계획 저장됨 · 선생님 승인 후 실행 가능"
                    : "규칙 데모의 다음 놀이 계획 저장됨 (AI 아님)");
            else agentStatus.setText("후속 놀이 계획 검증 실패 · 안전한 오프라인 계획 유지");
        });
    }

    private void showFinished() {
        pendingFeedbackSpeech = null;
        main.removeCallbacks(feedbackSpeechFallback);
        titleText.setText("수고했어요! 놀이 끝!");
        progressText.setText("5개 미션 모두 끝냈어요!");
        instructionsText.setText("정답 " + game.correctCount() + "/5개  ·  힌트 " + game.totalHints() + "회");
        countdownText.setText("선생님은 메뉴에서 최근 10회의 놀이 기록을 확인할 수 있어요.");
        cardsArea.removeAllViews();
        optionsArea.removeAllViews();
        feedbackText.setVisibility(View.VISIBLE);
        feedbackText.setText("끝까지 참여해 줘서 고마워! 다른 놀이도 해 보자.");
        startButton.setVisibility(View.VISIBLE);
        startButton.setText("다시 하기");
        hintButton.setVisibility(View.GONE);
        exampleButton.setVisibility(View.GONE);
        nextButton.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        speak("끝까지 참여해 줘서 고마워! 정말 잘했어!");
        gameScroll.post(() -> gameScroll.smoothScrollTo(0, 0));
    }

    private static String styleLabel(String style) {
        if (PlaySupportCoach.GENTLE.equals(style)) return "천천히 격려";
        if (PlaySupportCoach.CELEBRATE.equals(style)) return "성취 격려";
        return "차분한 안내";
    }

    private AgentMemoryPlanner makePlanner() {
        return approvedFollowUp == null ? new AgentMemoryPlanner()
                : new AgentMemoryPlanner(approvedFollowUp);
    }

    private void confirmQuit() {
        if (!runningSession || game.phase() == MemoryGame.Phase.FINISHED) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("게임 나가기")
                .setMessage("진행 중인 문제 기록은 저장되지 않아요. 게임을 그만할까요?")
                .setNegativeButton("계속 하기", null)
                .setPositiveButton("그만하기", (dialog, which) -> finish()).show();
    }

    private void speak(String message) {
        if (!robot.isReady()) return;
        // Previous prompt should not continue while the child sees a new phase.
        try {
            robot.cancelSpeech();
            robot.speak(message);
        } catch (RuntimeException e) {
            Toast.makeText(this, "음성 출력이 연결되지 않았어요. 화면으로 계속 진행합니다.", Toast.LENGTH_SHORT).show();
            if (message.equals(pendingIntroSpeech)) startPreviewTimer();
            if (message.equals(pendingQuestionSpeech)) finishQuestionSpeech();
            if (message.equals(pendingFeedbackSpeech)) finishFeedbackSpeech();
        }
    }

    /** Bundled drawings instead of depending on emoji fonts missing on older temi Android. */
    private boolean applyPicture(TextView view, MemoryGame.Item item, int dpSize) {
        int drawableId;
        switch (item.id) {
            case "dog": drawableId = R.drawable.pic_dog; break;
            case "cat": drawableId = R.drawable.pic_cat; break;
            case "rabbit": drawableId = R.drawable.pic_rabbit; break;
            case "bear": drawableId = R.drawable.pic_bear; break;
            case "apple": drawableId = R.drawable.pic_apple; break;
            case "banana": drawableId = R.drawable.pic_banana; break;
            case "grape": drawableId = R.drawable.pic_grape; break;
            case "strawberry": drawableId = R.drawable.pic_strawberry; break;
            case "seed": drawableId = R.drawable.pic_seed; break;
            case "water": drawableId = R.drawable.pic_water; break;
            case "bloom": drawableId = R.drawable.pic_bloom; break;
            case "washwater": drawableId = R.drawable.pic_washwater; break;
            case "soap": drawableId = R.drawable.pic_soap; break;
            case "rinse": drawableId = R.drawable.pic_rinse; break;
            case "ingredients": drawableId = R.drawable.pic_ingredients; break;
            case "mix": drawableId = R.drawable.pic_mix; break;
            case "salad": drawableId = R.drawable.pic_salad; break;
            default: return false; // e.g. ↖ position buttons already have text.
        }
        Drawable drawable = androidx.core.content.ContextCompat.getDrawable(this, drawableId);
        if (drawable == null) return false;
        drawable.setBounds(0, 0, dp(dpSize), dp(dpSize));
        view.setCompoundDrawables(null, drawable, null, null);
        view.setCompoundDrawablePadding(dp(2));
        view.setGravity(android.view.Gravity.CENTER);
        return true;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
