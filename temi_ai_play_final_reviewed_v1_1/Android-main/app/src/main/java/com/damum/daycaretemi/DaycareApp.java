package com.damum.daycaretemi;

import android.app.Application;
import android.content.Context;

import com.damum.daycaretemi.robot.RobotController;

/** 로봇은 하나 → RobotController도 앱 전체에 하나. 구현은 빌드(flavor)별 FlavorModule이 정한다. */
public class DaycareApp extends Application {

    private RobotController robot;

    @Override
    public void onCreate() {
        super.onCreate();
        robot = FlavorModule.createRobot(this);
        robot.start();
    }

    public static RobotController robot(Context context) {
        return ((DaycareApp) context.getApplicationContext()).robot;
    }
}
