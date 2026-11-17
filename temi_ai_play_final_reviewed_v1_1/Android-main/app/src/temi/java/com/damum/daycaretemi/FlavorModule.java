package com.damum.daycaretemi;

import android.app.Application;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.damum.daycaretemi.robot.RobotController;
import com.damum.daycaretemi.robot.TemiRobotController;

/** temi 빌드용. emulator 빌드의 같은 이름 클래스와 메서드 시그니처를 똑같이 유지할 것. */
public final class FlavorModule {

    private FlavorModule() {}

    static RobotController createRobot(Application app) {
        return new TemiRobotController();
    }

    /** temi 빌드에는 개발 패널 없음 */
    static void attachDevTools(AppCompatActivity activity, ViewGroup container) {
    }
}
