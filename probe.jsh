import org.localroute.core.*;
var pts = java.util.List.of(new GeoPoint(30.0,120.0), new GeoPoint(30.0,120.001), new GeoPoint(30.001,120.001), new GeoPoint(30.001,120.0));
for (var b : EndBehavior.values()) { var r = RouteCompiler.compile(pts, b); System.out.println(b+" v="+r.vertexCount()+" lap="+r.lapLengthMeters()+" path="+r.pathLengthMeters()); }
/exit