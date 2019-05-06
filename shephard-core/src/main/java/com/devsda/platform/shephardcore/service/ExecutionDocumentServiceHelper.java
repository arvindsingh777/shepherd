package com.devsda.platform.shephardcore.service;

import com.devsda.platform.shepherd.model.DataSourceDetails;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

public class ExecutionDocumentServiceHelper {

    private static final Logger log = LoggerFactory.getLogger(ExecutionDocumentServiceHelper.class);

    public static String createMongoConnectionUri(DataSourceDetails dataSourceDetails) {
        StringBuilder uriBuilder = new StringBuilder("");
        uriBuilder.append(dataSourceDetails.getPrefix()).append("://").append(dataSourceDetails.getUser()).append(":").append(dataSourceDetails.getPassword()).append("@");

        Iterator<String> it = dataSourceDetails.getClusters().iterator();

        if(it.hasNext()){
            uriBuilder.append(it.next());
        }

        while(it.hasNext()){
            uriBuilder.append(",").append(it.next());
        }

        uriBuilder.append(dataSourceDetails.getPath()).append("?").append(dataSourceDetails.getParams());
        return uriBuilder.toString();
    }

    public static MongoCollection<Document> getMongoCollection(MongoClient mongoClient, String dataBaseName, String collectionName) {
        if (mongoClient == null)
            return null;
        MongoDatabase db = mongoClient.getDatabase(dataBaseName);
        MongoCollection<Document> collection = db.getCollection(collectionName);
        return collection;
    }

}
