"""Generate Noise/transport interop vectors from Meta's reference Linux SDK.

Run from a checkout of facebookincubator/muse-gadget-sdk:
    PYTHONPATH=linux/src python3 tools/gen_noise_vectors.py > noise_vectors.json

Every key and chunk id is fixed, so the Kotlin port must reproduce each byte.
"""
import json
import struct
import sys

from cryptography.hazmat.primitives.asymmetric import x25519
from cryptography.hazmat.primitives import serialization

import musegadget.noise.noise_xx as nx
import musegadget.noise.framing as framing
from musegadget.noise import NoiseTransport, NoiseXXInitiator, NoiseXXResponder
from musegadget.noise.envelope import (
    ApplicationResponse, BodyChunk, ServiceFrame, ServiceResponse,
    encode_service_frame, encode_service_response,
)
from musegadget.noise.framing import encode_noise_frames
from musegadget.link_client import encode_message

PRIVS = {
    "init_e": bytes([0x11] * 32),
    "init_s": bytes([0x22] * 32),
    "resp_e": bytes([0x33] * 32),
    "resp_s": bytes([0x44] * 32),
}


def keypair(raw):
    priv = x25519.X25519PrivateKey.from_private_bytes(raw)
    pub = priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return nx._X25519KeyPair(private_key=priv, public_key_bytes=pub)


queue = []
nx._generate_x25519_key_pair = lambda: keypair(queue.pop(0))

chunk_ids = [0x0102030405060708, -5, 77, 123456789, 42]
framing.secrets.randbits = lambda bits: (chunk_ids.pop(0)) % (1 << 64)

# Handshake: initiator makes e (msg1), responder makes e then s (msg2), initiator makes s (msg3).
init = NoiseXXInitiator()
init.initialize()
resp = NoiseXXResponder(payload=b"hello device")
resp.initialize()
queue[:] = [PRIVS["init_e"]]
msg1 = init.write_message1()
queue[:] = [PRIVS["resp_e"], PRIVS["resp_s"]]
msg2 = resp.read_message1_and_write_message2(msg1)
payload2 = init.read_message2(msg2)
queue[:] = [PRIVS["init_s"]]
msg3 = init.write_message3()
resp.read_message3(msg3)
i_send, i_recv = init.split()
r_send, r_recv = resp.split()

device = NoiseTransport(i_send, i_recv)
control = device.start_stream_request("POST", "/link-control")
register = {"type": "req", "id": "reg-1", "method": "link.register", "params": {"node_id": "homelink-abcdef"}}
body = device.encrypt_body_chunk(control.stream_id, encode_message(register))
reset = device.encrypt_reset(control.stream_id)

# VM -> device: a 200 response head with a body, then an ending body chunk.
reply_msg = encode_message({"id": "reg-1", "result": {"ok": True}})
vm_frames = []
for frame in (
    ServiceFrame.response(1, ApplicationResponse(status=200, body=reply_msg)),
    ServiceFrame.body_chunk(1, BodyChunk(data=b"", end_body=True)),
):
    plain = encode_service_response(ServiceResponse(payload=encode_service_frame(frame)))
    for proto in encode_noise_frames(plain, chunk_id=9):
        vm_frames.append(r_send.encrypt_with_ad(b"", proto).hex())

json.dump({
    "privates": {k: v.hex() for k, v in PRIVS.items()},
    "responder_payload": "hello device",
    "msg1": msg1.hex(),
    "msg2": msg2.hex(),
    "msg3": msg3.hex(),
    "control_stream_id": control.stream_id,
    "control_frames": [f.hex() for f in control.frames],
    "register_json": json.dumps(register, separators=(",", ":")),
    "register_frames": [f.hex() for f in body],
    "reset_frames": [f.hex() for f in reset],
    "vm_frames": vm_frames,
    "vm_reply_json": json.dumps({"id": "reg-1", "result": {"ok": True}}, separators=(",", ":")),
}, sys.stdout, indent=1)
