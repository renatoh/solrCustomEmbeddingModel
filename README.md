# Enhancing Semantic Search (Text to Vector) in Solr
## Introduction

With Solr 9.9.0, you get end-to-end semantic vector search built directly into Solr.
By using a service like Hugging Face or Cohere, you can configure your model within Solr and get semantic search up and running quickly.
Vectorization, for both indexing and search, happens entirely within Solr, so there's no need to manually handle vectors anymore.
Despite this being a big step forward, you are still limited to third party Language Models hosted in the cloud from a few supported providers.

This might be suitable to get up and running quickly, but has a number of drawbacks, such as:

* Limited to models from supported providers**
* Privacy (Your data is sent to the cloud provider)**
* Latency


I’ve expanded on what Solr provides out of the box, allowing you to easily connect your custom model running on any endpoint.

## Additional Features
On top of that, I’ve added two new features that make vector search even more powerful.
### Support for Multiple Fields
You can combine multiple text fields into a single vector representation, for example, product-name, product-description, marketing-text, etc., can all be combined into a single vector. Simply configure the additional fields you want to include in your vector.

### Lazy Vectorization
When updating a document in Solr, the new vector for that document is only recreated if the content of a field used for the vector has changed. This is achieved by fetching the existing document within the update process and checking if any field used in the vector embedding has actually changed. The vector is only regenerated if any of the relevant fields has changed.
This minimizes expensive creation of vectors.


## Setup

### Create Custom Service
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

### Configure Solr for Vector Search

#### Configuration and Schema
Configure a vector field within you schema.xml
```
  <fieldType name="vector_1024" class="solr.DenseVectorField" vectorDimension="1024"                    
				   similarityFunction="cosine"  knnAlgorithm="hnsw" hnswMaxConnections="16" hnswBeamWidth="100"/>
  <field name="vector_en" type="vector_1024" indexed="true" stored="true" multiValued="false" />
```
Add UpdateProcessor to solrconfig.xml and register it in your updateRequestProcessorChain. Here you can add the additiona fiedls, in this example we add manu_s and description_s. Thus he vector embedding will bee create out of the name_s, manu_s and description_s
Additionally the TextToVectorQParserPlugin need to be registred.
```
<updateProcessor name="textToVector" class="custom.solr.llm.textvectorisation.update.processor.LazyMultiFieldTextToVectorUpdateProcessorFactory">
   <str name="inputField">name_s</str>
   <str name="additionalInputField">manu_s,description_s</str>
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

<queryParser name="knn_text_to_vector" class="org.apache.solr.llm.textvectorisation.search.TextToVectorQParserPlugin"/>
```


####  Configure Custom Model
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

### Deploying Custom jar
Deploy jar with custom classes to Solr [solrcustomeembeddingmodel.jar](https://github.com/renatoh/solrCustomEmbeddingModel/blob/main/artifact/solrcustomeembeddingmodel.jar)
This jar just contains following classes:<br>
 [CustomModel.java](https://github.com/renatoh/solrCustomEmbeddingModel/blob/main/src/main/java/custom/solr/llm/textvectorisation/model/CustomModel.java)<br>
 [LazyMultiFieldTextToVectorUpdateProcessor.java](https://github.com/renatoh/solrCustomEmbeddingModel/blob/main/src/main/java/custom/solr/llm/textvectorisation/update/processor/LazyMultiFieldTextToVectorUpdateProcessor.java)<br> [LazyMultiFieldTextToVectorUpdateProcessorFactory.java](https://github.com/renatoh/solrCustomEmbeddingModel/blob/main/src/main/java/custom/solr/llm/textvectorisation/update/processor/LazyMultiFieldTextToVectorUpdateProcessorFactory.java) 

### Running KNN Query  

####KNN Query
```
GET /solr/products/select?
q={!knn_text_to_vector f=vector_en model=customLocal topK50}something to wear for an male adult&
fl=code_s,title_txt_en&rows=25
````
#### Hybrid Query
```
GET /solr/products/select?
q={!bool filter=$retrievalStage must=$rankingStage}&
fl=code_s,title_t,score,lexicalScore:query($normalisedLexicalQuery)&
retrievalStage={!bool should=$lexicalQuery should=$vectorQuery}&
rankingStage={!func}product(query($normalisedLexicalQuery),query($vectorQuery))&
normalisedLexicalQuery={!func}scale(query($lexicalQuery),0.1,1)&
lexicalQuery={!type=edismax qf=title_txt_en}shirt&
vectorQuery={!knn_text_to_vector f=vector_en model=customLocal}shirt
```

