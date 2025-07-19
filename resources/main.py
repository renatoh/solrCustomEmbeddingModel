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

# start with:
# uvicorn main:app --host 0.0.0.0 --port 8000
