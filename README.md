# Howto

## Create Custom Service
Spin up your own service providing an enpoint to for vector embedding. Here we use a little Python script downloading the model 'WhereIsAI/UAE-Large-V1' from Hugging Face
```
from fastapi import FastAPI, Request
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

app = FastAPI()
model = SentenceTransformer("WhereIsAI/UAE-Large-V1")

class TextInput(BaseModel):
    inputs: str

@app.post("/embed")
def embed_text(input: TextInput):
    embedding = model.encode(input.inputs).tolist()
    return  embedding
```

## Configure Solr for Vector Search

Configure a vector field within you schema.xml
```
  <fieldType name="vector_1024" class="solr.DenseVectorField" vectorDimension="1024"                    
				   similarityFunction="cosine"  knnAlgorithm="hnsw" hnswMaxConnections="16" hnswBeamWidth="100"/>
  <field name="vector_en" type="vector_1024" indexed="true" stored="true" multiValued="false" />
```
Add UpdateProcessor to solrconfig.xml and register it in your updateRequestProcessorChain
```
  <updateProcessor name="textToVector" class="custom.solr.llm.textvectorisation.update.processor.LazyMultiFieldTextToVectorUpdateProcessorFactory">
   <str name="inputField">name_s</str>
   <str name="additionalInputField">manu_s</str>
   <str name="outputField">vector_en</str>
   <str name="model">customLocal</str>
  </updateProcessor>
  
  <!-- The update.autoCreateFields property can be turned to false to disable schemaless mode -->
  <updateRequestProcessorChain name="add-unknown-fields-to-the-schema" default="${update.autoCreateFields:true}"
           processor="uuid,textToVector,remove-blank,field-name-mutating,max-fields,parse-boolean,parse-long,parse-double,parse-date,add-schema-fields">
    <processor class="solr.LogUpdateProcessorFactory"/>
    <processor class="solr.DistributedUpdateProcessorFactory"/>
    <processor class="solr.RunUpdateProcessorFactory"/>
  </updateRequestProcessorChain>
```


## 1. Configure Custom Model
Create you custom model conofiguration and push it to Solr
```
{
  "class": "custom.solr.llm.textvectorisation.model.CustomEmbeddingModel",
  "name": "customLocal",
  "params": {
    "endpointUrl": "http://localhost:8000/embed",
    "fieldName": "inputs"
  }
}
```
Push Json above to Solr model store
```
curl -XPUT 'http://localhost:8983/solr/techproducts/schema/text-to-vector-model-store' --data-binary "@custom.json" -H 'Content-type:application/json'
```
   

