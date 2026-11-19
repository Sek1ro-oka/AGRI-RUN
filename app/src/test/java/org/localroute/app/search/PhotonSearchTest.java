package org.localroute.app.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PhotonSearchTest {

    @Test
    public void parsesNameAddressAndCoordinates() throws Exception {
        String json = """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{
                    "name":"浙江农林大学(东湖校区)","street":"武肃街","housenumber":"666号",
                    "district":"锦北街道","city":"杭州市","state":"浙江省","country":"中国"},
                   "geometry":{"type":"Point","coordinates":[119.7237543,30.2588962]}},
                  {"type":"Feature","properties":{
                    "street":"育英路","housenumber":"12号","district":"锦城街道","city":"杭州市"},
                   "geometry":{"type":"Point","coordinates":[119.72,30.24]}}
                ]}
                """;

        List<PhotonSearch.Place> places = PhotonSearch.parse(json);

        assertEquals(2, places.size());
        assertEquals("浙江农林大学(东湖校区)", places.get(0).name());
        assertEquals("武肃街666号 · 锦北街道 · 杭州市 · 浙江省", places.get(0).address());
        assertEquals(30.2588962, places.get(0).latitude(), 1e-7);
        assertEquals(119.7237543, places.get(0).longitude(), 1e-7);

        assertEquals("育英路12号", places.get(1).name());
        assertEquals("育英路12号 · 锦城街道 · 杭州市", places.get(1).address());
    }

    @Test
    public void skipsFeaturesWithoutUsableGeometry() throws Exception {
        String json = """
                {"features":[
                  {"properties":{"name":"无坐标"},"geometry":null},
                  {"properties":{"name":"坐标越界"},"geometry":{"coordinates":[200,30]}},
                  {"properties":{"name":"缺坐标"},"geometry":{}}
                ]}
                """;

        assertTrue(PhotonSearch.parse(json).isEmpty());
    }

    @Test
    public void fallsBackToCityAndSkipsEmptyEntries() throws Exception {
        String json = """
                {"features":[
                  {"properties":{"city":"杭州市"},"geometry":{"coordinates":[119.72,30.25]}},
                  {"properties":{},"geometry":{"coordinates":[119.72,30.25]}}
                ]}
                """;

        List<PhotonSearch.Place> places = PhotonSearch.parse(json);
        assertEquals(1, places.size());
        assertEquals("杭州市", places.get(0).name());
    }

    @Test
    public void missingFeaturesReturnsEmptyList() throws Exception {
        assertTrue(PhotonSearch.parse("{}").isEmpty());
        assertTrue(PhotonSearch.parse("{\"features\":[]}").isEmpty());
    }
}
