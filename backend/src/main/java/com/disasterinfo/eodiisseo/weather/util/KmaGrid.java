package com.disasterinfo.eodiisseo.weather.util;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;

/** KMA Lambert conformal conic grid; reference point (126E, 38N) is (43, 136). */
public record KmaGrid(int x,int y) {
    public static KmaGrid from(double latitude,double longitude) {
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||latitude < -90||latitude>90||longitude < -180||longitude>180) throw ApiProblem.bad("좌표를 확인하세요.");
        double rad=Math.PI/180,earth=6371.00877/5,phi1=30*rad,phi2=60*rad,originLat=38*rad,originLon=126*rad;
        double sn=Math.log(Math.cos(phi1)/Math.cos(phi2))/Math.log(Math.tan(Math.PI/4+phi2/2)/Math.tan(Math.PI/4+phi1/2));
        double sf=Math.pow(Math.tan(Math.PI/4+phi1/2),sn)*Math.cos(phi1)/sn;
        double ro=earth*sf/Math.pow(Math.tan(Math.PI/4+originLat/2),sn);
        double ra=earth*sf/Math.pow(Math.tan(Math.PI/4+latitude*rad/2),sn);
        double theta=longitude*rad-originLon;
        if(theta>Math.PI) theta-=2*Math.PI;if(theta < -Math.PI) theta+=2*Math.PI;theta*=sn;
        return new KmaGrid((int)Math.floor(ra*Math.sin(theta)+43+0.5),(int)Math.floor(ro-ra*Math.cos(theta)+136+0.5));
    }
}
