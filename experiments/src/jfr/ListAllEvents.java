package jfr;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class ListAllEvents {
    public static void main(String[] args) throws Exception {



        Map<String, Integer> events = new HashMap<>();

        try (RecordingFile recordingFile = new RecordingFile(Paths.get("minimal.war.load-4m-Marh27-10AM-TotalAndThreads.jfr"))) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                events.compute(event.getEventType().getName(), (k, v) -> (v == null ? 1 : ++v));
            }
        }
        ArrayList<Map.Entry<String, Integer>> list = new ArrayList<>(events.entrySet());

        list.sort(Collections.reverseOrder(Comparator.comparingInt(Map.Entry::getValue)));

        for (int i = 0; i < list.size(); i++) {
            Map.Entry<String, Integer> entry = list.get(i);
            System.out.println(entry.getKey() + " -> " + entry.getValue());
        }
    }

}
