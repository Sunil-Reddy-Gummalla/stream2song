package com.stream2song.backend.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.stream2song.backend.client.JavaHttpReplicateTransport;
import com.stream2song.backend.config.MusicProperties;

@Component
public class ReplicateMusicGenerationProvider implements MusicGenerationProvider {
    private static final URI CREATE=URI.create("https://api.replicate.com/v1/models/minimax/music-2.5/predictions");
    private static final String API="https://api.replicate.com/v1/predictions/";
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final int API_CAP=1_000_000, AUDIO_CAP=20*1024*1024;
    private final MusicProperties properties; private final ObjectMapper mapper; private final ReplicateHttpTransport transport;
    public ReplicateMusicGenerationProvider(MusicProperties properties,ObjectMapper mapper,ReplicateHttpTransport transport){this.properties=properties;this.mapper=mapper;this.transport=transport;}
    public boolean isConfigured(){return properties.isConfigured();}
    public String fingerprint(){return "replicate:minimax/music-2.5:audio_format=mp3:sample_rate=44100:bitrate=128000:v1";}
    public Prediction submit(String lyrics,String prompt){
        if(!isConfigured())throw new MusicGenerationFailure(503,"music_provider_not_configured",false);
        if(lyrics==null||lyrics.length()>3500||prompt==null||prompt.length()>2000)throw new MusicGenerationFailure(422,"music_input_too_long",false);
        try {
            byte[] body=mapper.writeValueAsBytes(Map.of("input",Map.of("lyrics",lyrics,"prompt",prompt,"audio_format","mp3","sample_rate",44100,"bitrate",128000)));
            var response=transport.exchange("POST",CREATE,properties.replicateApiToken(),body,Map.of("Cancel-After","15m"),API_CAP,45);
            if(response.status()==401)throw new MusicGenerationFailure(502,"music_provider_auth_failed",false);
            if(response.status()==403)throw new MusicGenerationFailure(502,"music_provider_access_denied",false);
            if(response.status()==402)throw new MusicGenerationFailure(502,"music_provider_billing_required",false);
            if(response.status()==400||response.status()==422)throw new MusicGenerationFailure(422,"music_provider_input_invalid",false);
            if(response.status()==429)throw new MusicGenerationFailure(503,"music_provider_rate_limited",false);
            if(response.status()>=500)throw new MusicGenerationFailure(502,"music_submission_indeterminate",true);
            if(response.status()<200||response.status()>=300)throw new MusicGenerationFailure(502,"music_provider_rejected",false);
            try{return parse(response.body());}catch(MusicGenerationFailure e){throw new MusicGenerationFailure(502,"music_submission_indeterminate",true);}
        } catch(MusicGenerationFailure e){throw e;}
        catch(JavaHttpReplicateTransport.TransportFailure e){throw new MusicGenerationFailure(e.timeout()?504:502,"music_submission_indeterminate",true);}
        catch(RuntimeException e){throw new MusicGenerationFailure(502,"music_submission_indeterminate",true);}
    }
    public Prediction poll(String id){
        if(!isConfigured())throw new MusicGenerationFailure(503,"music_provider_not_configured",false);
        if(id==null||!ID.matcher(id).matches())throw new MusicGenerationFailure(502,"music_provider_response_invalid",false);
        try{
            var response=transport.exchange("GET",URI.create(API+id),properties.replicateApiToken(),null,Map.of(),API_CAP,30);
            if(response.status()==429||response.status()>=500)throw new MusicGenerationFailure(503,"music_provider_poll_retry",false);
            if(response.status()<200||response.status()>=300)throw new MusicGenerationFailure(502,"music_provider_failed",false);
            Prediction prediction=parse(response.body()); if(!id.equals(prediction.id()))throw new MusicGenerationFailure(502,"music_provider_response_invalid",false); return prediction;
        }catch(MusicGenerationFailure e){throw e;}
        catch(JavaHttpReplicateTransport.TransportFailure e){throw new MusicGenerationFailure(e.timeout()?504:502,e.oversized()?"music_provider_response_invalid":"music_provider_poll_retry",false);}
        catch(RuntimeException e){throw new MusicGenerationFailure(502,"music_provider_response_invalid",false);}
    }
    public byte[] download(URI uri){
        validateOutputUri(uri);
        try{
            var response=transport.exchange("GET",uri,null,null,Map.of(),AUDIO_CAP,60);
            if(response.status()==403||response.status()==404||response.status()==410)throw new MusicGenerationFailure(410,"music_output_expired",false);
            if(response.status()<200||response.status()>=300)throw new MusicGenerationFailure(502,"music_output_unavailable",false);
            String type=response.contentType().split(";",2)[0].trim().toLowerCase(java.util.Locale.ROOT);
            byte[] audio=response.body();
            if(!(type.equals("audio/mpeg")||type.equals("application/octet-stream"))||!isMp3(audio))throw new MusicGenerationFailure(502,"music_output_invalid",false);
            return audio;
        }catch(MusicGenerationFailure e){throw e;}
        catch(JavaHttpReplicateTransport.TransportFailure e){throw new MusicGenerationFailure(e.timeout()?504:502,e.oversized()?"music_output_too_large":"music_output_unavailable",false);}
    }
    static void validateOutputUri(URI uri){
        String host=uri==null||uri.getHost()==null?null:uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if(uri==null||!"https".equalsIgnoreCase(uri.getScheme())||uri.getPort()!=-1&&uri.getPort()!=443||uri.getUserInfo()!=null||host==null
                ||!(host.equals("replicate.delivery")||host.endsWith(".replicate.delivery")))
            throw new MusicGenerationFailure(502,"music_output_invalid",false);
    }
    private boolean isMp3(byte[] bytes){
        if(bytes==null||bytes.length<4)return false;
        int searchStart=0;
        if(bytes.length>=10&&bytes[0]=='I'&&bytes[1]=='D'&&bytes[2]=='3'){
            int version=bytes[3]&255;if(version<2||version>4)return false;
            for(int i=6;i<10;i++)if((bytes[i]&0x80)!=0)return false;
            int tagSize=((bytes[6]&0x7f)<<21)|((bytes[7]&0x7f)<<14)|((bytes[8]&0x7f)<<7)|(bytes[9]&0x7f);
            searchStart=10+tagSize;if(searchStart>bytes.length-4)return false;
        }
        for(int i=searchStart;i<=Math.min(bytes.length-4,4096);i++){
            int header=((bytes[i]&255)<<24)|((bytes[i+1]&255)<<16)|((bytes[i+2]&255)<<8)|(bytes[i+3]&255);
            int sync=(header>>>21)&0x7ff,version=(header>>>19)&3,layer=(header>>>17)&3,bitrate=(header>>>12)&15,sample=(header>>>10)&3;
            if(sync==0x7ff&&version!=1&&layer!=0&&bitrate!=0&&bitrate!=15&&sample!=3)return true;
        }
        return false;
    }
    private Prediction parse(byte[] bytes){
        if(bytes==null||bytes.length>API_CAP)throw new MusicGenerationFailure(502,"music_provider_response_invalid",false);
        try{JsonNode root=mapper.readTree(bytes);String id=root.path("id").asText();String status=root.path("status").asText();if(!ID.matcher(id).matches()||!java.util.Set.of("starting","processing","succeeded","failed","canceled").contains(status))throw new MusicGenerationFailure(502,"music_provider_response_invalid",false);
            URI output=null;JsonNode out=root.path("output");if(out.isTextual()&&!out.asText().isBlank())output=URI.create(out.asText());return new Prediction(id,status,output);
        }catch(MusicGenerationFailure e){throw e;}catch(Exception e){throw new MusicGenerationFailure(502,"music_provider_response_invalid",false);}
    }
}
