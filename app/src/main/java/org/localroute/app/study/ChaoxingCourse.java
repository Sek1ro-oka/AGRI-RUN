package org.localroute.app.study;

/**
 * 学习通课程列表里的一门课。作业入口按 courseId + classId + cpi 定位。
 */
public final class ChaoxingCourse {

    public final String courseId;
    public final String classId;
    public final String cpi;
    public final String name;
    public final String teacher;

    public ChaoxingCourse(String courseId, String classId, String cpi,
                          String name, String teacher) {
        this.courseId = courseId;
        this.classId = classId;
        this.cpi = cpi;
        this.name = name;
        this.teacher = teacher;
    }

    public String courseUrl() {
        return "https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=" + courseId
                + "&clazzid=" + classId + "&cpi=" + cpi + "&ismooc2=1&v=2";
    }
}
