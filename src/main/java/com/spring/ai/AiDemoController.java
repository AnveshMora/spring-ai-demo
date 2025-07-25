package com.spring.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.model.Media;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.audio.speech.SpeechModel;
import org.springframework.ai.openai.audio.speech.SpeechPrompt;
import org.springframework.ai.openai.audio.speech.SpeechResponse;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
public class AiDemoController {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final ChatClient client;
    private final SpeechModel speechModel;
    private final VectorStore vectorStore;
    private final ImageModel imageModel;

    private static final String DIR_IN = "/Users/spring-ai-demo/files/in";
    private static final String DIR_OUT = "/Users/spring-ai-demo/files/out";

    public AiDemoController(ChatClient.Builder builder, OpenAiAudioSpeechModel speechModel, VectorStore vectorStore, ImageModel imageModel) {
        // We'll revisit this later. This is going to be legen...wait for it...
        //this.client = builder.build(); ...DARY!
        this.client = builder.defaultAdvisors(
                        new MessageChatMemoryAdvisor(new InMemoryChatMemory(), "default", 10))
                .build();

        this.speechModel = speechModel;
        this.vectorStore = vectorStore;
        this.imageModel = imageModel;
    }

    @GetMapping
    public String getString(@RequestParam(defaultValue = "What is the meaning of life?") String message) {
        return client.prompt()
                .user(message)
                .call()
                .content();
    }

    @GetMapping("/response")
    public ChatResponse getResponse(@RequestParam(defaultValue = "What is the meaning of life?") String message) {
        return client.prompt()
                .user(message)
                .call()
                .chatResponse();
    }

    @GetMapping("/template")
    public String getTemplateResponse(@RequestParam String type,
                                      @RequestParam String topic) {
        return client.prompt()
                .user(u -> u.text("Write a {type} about {topic}, 1000 word limit.")
                        .param("type", type).param("topic", topic))
                .call()
                .content();
    }

    @GetMapping("/rag")
    public String getRagResponseFromOurData(@RequestParam(defaultValue = "Airspeeds") String message) {
        return client.prompt()
                .user(message)
                .advisors(new QuestionAnswerAdvisor(vectorStore))
                .call()
                .content();
    }

    /*
       I think all services should be stateless.
       Why do you think that?
       Why do I think what?
    */
    @GetMapping("/conversation")
    public String getConversation(@RequestParam(defaultValue = "What is the meaning of life?") String message,
                                        @RequestParam(defaultValue = "default") String conversationId) {
        return client.prompt()
                .user(message)
                .advisors(as -> as.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                .call()
                .content();
    }

    @GetMapping("/translate")
    public String getTranslation(@RequestParam(defaultValue = "What is the meaning of life?") String message,
                                 @RequestParam(defaultValue = "English") String language,
                                 @RequestParam(defaultValue = "false") boolean save) throws IOException {
        var content = client.prompt()
                .user(message)
                .system(s -> s.text("You respond in {language}").param("language", language))
                .call()
                .content();

        if (save) convertToSpeech(content, DIR_OUT + String.format("/TTS_Output_%s.mp3", language));

        return content;
    }

    private void convertToSpeech(String content, String outputDest) throws IOException {
        var fsr = new FileSystemResource(outputDest);
        var outputStream = new ByteArrayOutputStream();

        var counter = new AtomicInteger(1);
        int numberOfSegments = (int) Math.ceil((double) content.length() / 4096);
        while (!content.isEmpty()) {
            logger.info(String.format("Converting segment %d of %d to audio", counter.getAndIncrement(), numberOfSegments));

            var textToSpeech = content.substring(0, Math.min(content.length(), 4096));
            if (textToSpeech.length() == 4096) {
                textToSpeech = textToSpeech.substring(0, textToSpeech.lastIndexOf(' '));
            }
            content = content.substring(textToSpeech.length());

            SpeechResponse response = speechModel.call(new SpeechPrompt(textToSpeech));
            outputStream.write(response.getResult().getOutput());
        }

        fsr.getOutputStream().write(outputStream.toByteArray());
    }

    @GetMapping("/docaudio")
    public String convertDocToAudio(@RequestParam String filepath) throws IOException {
        var importFile = filepath.startsWith("http")
                ? new UrlResource(filepath)
                : new FileSystemResource(filepath);
        var infile = importFile.getFilename() != null
                ? importFile.getFilename().contains(".")
                ? importFile.getFilename().substring(0, importFile.getFilename().lastIndexOf('.'))
                : importFile.getFilename()
                : "DocAudio";

        logger.info("Processing " + importFile.getFilename());

        var documents = new TikaDocumentReader(importFile).get();
        logger.info(String.format("Converting to audio and saving %d file(s) to %s", documents.size(), DIR_OUT));

        var counter = new AtomicInteger(1);
        for (Document doc : documents) {
            logger.info(String.format("Processing document %d, %d characters.", counter.get(), doc.getFormattedContent().length()));
            convertToSpeech(doc.getFormattedContent(),
                    String.format("%s/%s_%d.mp3", DIR_OUT, infile, counter.getAndIncrement()));
        }

        logger.info("Audio conversion and save complete for " + infile);
        return "Audio conversion and save complete for " + infile;
    }

    @GetMapping("/mm")
    public String getImageDescription(@RequestParam(defaultValue = DIR_IN + "/testimage.jpg") String imagePath,
                                      @RequestParam(defaultValue = "What is in this image?") String message) throws MalformedURLException {
        // For sample URL, try this (courtesy of Spring AI docs): "https://docs.spring.io/spring-ai/reference/1.0-SNAPSHOT/_images/multimodal.test.png"
        // For sample local file, provide full filepath
        // Keeping it simple, only accept JPEGs and PNGs
        var imageType = imagePath.endsWith(".jpg") ? MimeTypeUtils.IMAGE_JPEG : MimeTypeUtils.IMAGE_PNG;
        var media = (imagePath.startsWith("http") ?
                new Media(imageType, new URL(imagePath)) :
                new Media(imageType, new FileSystemResource(imagePath)));

        return client.prompt()
                .user(c -> c.text(message).media(media))
                .call()
                .content();
    }

    @GetMapping("/mmrag")
    public String getMultimodalRagResponse(@RequestParam(defaultValue = DIR_IN + "/testimage.jpg") String imagePath,
                                           @RequestParam(defaultValue = "Tell me everything you can about this image") String message) throws MalformedURLException {
        return getRagResponseFromOurData(getImageDescription(imagePath, message));
    }

    @GetMapping("/image")
    public ImageResponse createImage(@RequestParam(defaultValue = "Two dogs playing chess") String description) {
        return imageModel.call(new ImagePrompt(description));
    }
}
