package com.damum.daycaretemi;

import android.app.Application;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.damum.daycaretemi.dev.DevPanel;
import com.damum.daycaretemi.robot.FakeRobotController;
import com.damum.daycaretemi.robot.RobotController;

/** emulator 빌드용. temi 빌드의 같은 이름 클래스와 메서드 시그니처를 똑같이 유지할 것. */
public final class FlavorModule {

    private FlavorModule() {}

    static RobotController createRobot(Application app) {
        return new FakeRobotController();
    }

    /** 화면 구석 개발 패널(이벤트 버튼) + 하단 로그 영역 */
    static void attachDevTools(AppCompatActivity activity, ViewGroup container) {
        DevPanel.attach(activity, container);
    }
}
