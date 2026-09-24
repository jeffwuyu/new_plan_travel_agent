package com.travelagent.client.web;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class WebSearchQuery {
    String attraction;
    String city;
    String date;
    String infoType;
    String queryText;
    int topK;
}
