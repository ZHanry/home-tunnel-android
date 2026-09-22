#include "home_tunnel/remote.h"
#include "protocol.hpp"
#include "session_gate.hpp"
#include "../generated/test_vectors.hpp"
#include <array>
#include <cstdio>
#include <cstdlib>
#include <tuple>
#include <string>
#include <vector>

using namespace ht::rd;
#define CHECK(expression) do { if (!(expression)) { std::fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #expression); std::exit(1); } } while (false)
namespace {
struct Sink : InputSink {
    std::vector<std::tuple<uint16_t, bool, bool>> calls;
    bool fail = false;
    bool key(uint16_t code, bool down, bool repeat) override { calls.emplace_back(code, down, repeat); return !fail; }
    bool button(uint8_t, bool) override { return !fail; }
};
void put32(std::vector<uint8_t>& bytes, size_t offset, uint32_t value) {
    for (unsigned n=0; n<4; ++n) bytes[offset+n] = static_cast<uint8_t>(value >> (24-n*8));
}
std::vector<uint8_t> key(uint32_t epoch=1, uint32_t input=1, uint32_t sequence=1, bool down=true) {
    std::vector<uint8_t> bytes{0x52,0x44,1,0x20,0,0,0,24,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,8,0,7,0,0xe0,1,0,0,0};
    put32(bytes,8,epoch);put32(bytes,12,input);put32(bytes,16,sequence);bytes[28]=down ? 1 : 0;
    return bytes;
}
void ready(SessionGate& session, uint64_t now=1000, uint64_t scopes=protocol::PERMISSION_VIEW|protocol::PERMISSION_INPUT_KEYBOARD) {
    CHECK(session.authorize({1,1,scopes,100000,1000000},100000,now)==GateResult::ok);
    CHECK(!session.media_allowed());
    CHECK(session.peer_authenticated(1,now)==GateResult::ok);
    CHECK(!session.media_allowed());
    CHECK(session.selected_pair(1,{"udp",Candidate::host,Candidate::peer_reflexive,true,true,1},now)==GateResult::ok);
    CHECK(session.media_allowed());CHECK(!session.input_allowed());
    CHECK(session.first_frame(1,now)==GateResult::ok);
    CHECK(session.synchronize_input(1,1,1,now)==GateResult::ok);
}
void framing() {
    Frame frame;
    auto bytes=key();
    CHECK(parse_frame(bytes,Channel::input,1,frame)==FrameError::ok);
    for (size_t size=0;size<bytes.size();++size) CHECK(parse_frame(std::span(bytes).first(size),Channel::input,1,frame)!=FrameError::ok);
    CHECK(parse_frame(bytes,Channel::motion,1,frame)==FrameError::channel);
    CHECK(parse_frame(bytes,Channel::input,2,frame)==FrameError::epoch);
    bytes[4]=1;CHECK(parse_frame(bytes,Channel::input,1,frame)==FrameError::flags);bytes[4]=0;
    bytes[31]=1;CHECK(parse_frame(bytes,Channel::input,1,frame)==FrameError::payload);bytes[31]=0;
    put32(bytes,16,0xfffff000);CHECK(parse_frame(bytes,Channel::input,1,frame)==FrameError::sequence);
    CHECK(valid_utf8(std::array<uint8_t,7>{0xe4,0xb8,0xad,0xf0,0x9f,0x98,0x80}));
    for (const auto& invalid:std::vector<std::vector<uint8_t>>{{0xc0,0x80},{0xed,0xa0,0x80},{0xf4,0x90,0x80,0x80},{0xe4,0xb8},{0xff}}) CHECK(!valid_utf8(invalid));
    // Bounded exhaustive single-byte mutations exercise every header field and truncated payload.
    const auto original=key();
    for (size_t i=0;i<original.size();++i) for (unsigned value=0;value<256;++value) {
        bytes=original;bytes[i]=static_cast<uint8_t>(value);
        (void)parse_frame(bytes,Channel::input,1,frame);
    }
}
void watchdog_and_epoch() {
    Sink sink;SessionGate session(sink);ready(session);
    CHECK(session.accept_key(key(),1001)==GateResult::ok);
    CHECK(sink.calls.size()==1);
    CHECK(session.tick(2999)==GateResult::ok);CHECK(session.input_allowed());
    CHECK(session.tick(3000)==GateResult::ok);CHECK(!session.input_allowed());
    CHECK(sink.calls.size()==2 && !std::get<1>(sink.calls.back()));
    CHECK(session.accept_key(key(1,1,2),3001)==GateResult::state);
    CHECK(session.synchronize_input(1,1,1,3002)==GateResult::state);
    CHECK(session.synchronize_input(1,2,1,3002)==GateResult::ok);
    CHECK(session.accept_key(key(1,1,1),3003)==GateResult::replay);
    CHECK(session.accept_key(key(1,2,1),3003)==GateResult::ok);
    const auto deadline=session.deadline_ms();
    CHECK(session.reconnect(2,3004)==GateResult::ok);CHECK(session.deadline_ms()==deadline);
    CHECK(!session.media_allowed() && !session.input_allowed());
    CHECK(session.peer_authenticated(1,3005)==GateResult::identity);
    CHECK(session.tick(deadline)==GateResult::expired);
    CHECK(session.renew({2,2,3,1000000,1900000},1000000,deadline)==GateResult::closed);
}
void network_gate() {
    for (const auto candidate: {Candidate::relay,Candidate::unknown}) {
        Sink sink;SessionGate session(sink);ready(session);
        CHECK(session.accept_key(key(),1001)==GateResult::ok);
        CHECK(session.selected_pair(1,{"udp",Candidate::host,candidate,true,true,2},1002)==GateResult::path);
        CHECK(!session.media_allowed() && !session.input_allowed());CHECK(sink.calls.size()==2);
        CHECK(session.selected_pair(1,{"udp",Candidate::host,Candidate::host,true,true,1},1003)==GateResult::replay);
    }
    Sink sink;SessionGate session(sink);ready(session);
    CHECK(session.selected_pair(1,{"tcp",Candidate::host,Candidate::host,true,true,2},1002)==GateResult::path);
    CHECK(!session.media_allowed());
    CHECK(session.selected_pair(1,{"udp",Candidate::host,Candidate::host,false,true,3},1003)==GateResult::path);
    CHECK(session.selected_pair(1,{"udp",Candidate::host,Candidate::host,true,true,4},1004)==GateResult::ok);
    CHECK(!session.input_allowed());
}
void lease_and_isolation() {
    Sink first,second;SessionGate a(first),b(second);ready(a);ready(b);
    CHECK(a.accept_key(key(),1001)==GateResult::ok);
    CHECK(b.accept_key(key(),1001)==GateResult::ok);
    a.close();CHECK(!a.media_allowed() && b.input_allowed());CHECK(first.calls.size()==2 && second.calls.size()==1);
    const auto deadline=b.deadline_ms();
    CHECK(b.renew({1,1,3,100100,1000100},100100,1100)==GateResult::identity);
    CHECK(b.deadline_ms()==deadline);
    CHECK(b.renew({1,2,protocol::ALL_PERMISSIONS,100100,1000100},100100,1100)==GateResult::identity);
    CHECK(b.renew({1,2,protocol::PERMISSION_VIEW,100100,1000100},100100,1100)==GateResult::ok);
    CHECK(!b.input_allowed() && second.calls.size()==2);
    CHECK(b.tick(1099)==GateResult::expired); // Monotonic clock rollback cannot extend a lease.
    Sink readonly;SessionGate c(readonly);ready(c,1000,protocol::PERMISSION_VIEW);
    CHECK(c.accept_key(key(),1001)==GateResult::permission);CHECK(readonly.calls.empty());
}
void abi_contract() {
    CHECK(ht_rd_abi_version()==1);
    ht_rd_handle handle=99;
    ht_rd_config_v1 config{sizeof(config),1,1,0};
    ht_rd_callbacks_v1 callbacks{sizeof(callbacks),1,nullptr,nullptr};
    config.abi_version=2;CHECK(ht_rd_create(&config,&callbacks,&handle)==HT_RD_ABI_MISMATCH && handle==0);config.abi_version=1;
    CHECK(ht_rd_create(&config,&callbacks,&handle)==HT_RD_OK && handle!=0);
    ht_rd_capabilities_v1 capabilities{};capabilities.size=sizeof(capabilities);capabilities.abi_version=1;
    CHECK(ht_rd_get_capabilities(handle,&capabilities)==HT_RD_OK);
    CHECK(!capabilities.available && !capabilities.can_host && capabilities.reason==HT_RD_BACKEND_UNAVAILABLE);
    const std::array<uint8_t,1> fake{0};CHECK(ht_rd_start(handle,fake.data(),fake.size())==HT_RD_BACKEND_UNAVAILABLE);
    CHECK(ht_rd_start(handle,nullptr,1)==HT_RD_INVALID_ARGUMENT);
    CHECK(ht_rd_close(handle,0)==HT_RD_OK);CHECK(ht_rd_close(handle,0)==HT_RD_OK);
    CHECK(ht_rd_submit_input(handle,fake.data(),fake.size())==HT_RD_STATE_CONFLICT);
    ht_rd_release(handle);ht_rd_release(handle);
    CHECK(ht_rd_get_capabilities(handle,&capabilities)==HT_RD_INVALID_HANDLE);
}
void transcript() {
    std::array<uint8_t,16> id{};std::array<std::array<uint8_t,32>,5> fields{};
    const auto bytes=proof_transcript(id,7,fields);
    CHECK(bytes.size()==222 && read_u32(bytes,0)==14);
    CHECK(read_u32(bytes,18)==16 && read_u32(bytes,38)==7 && read_u32(bytes,42)==32);
    CHECK(bytes!=proof_transcript(id,8,fields));
    id={0x00,0x11,0x22,0x33,0x44,0x55,0x46,0x77,0x88,0x99,0xaa,0xbb,0xcc,0xdd,0xee,0xff};
    for(size_t n=0;n<fields.size();++n) for(size_t i=0;i<32;++i) fields[n][i]=static_cast<uint8_t>(n*32+i);
    const auto actual=proof_transcript(id,3,fields);
    std::string hex;
    for (auto byte:actual) { hex.push_back("0123456789abcdef"[byte>>4]);hex.push_back("0123456789abcdef"[byte&15]); }
    CHECK(hex==protocol::PROOF_VECTOR_HEX);
}
}
int main() {
    framing();watchdog_and_epoch();network_gate();lease_and_isolation();abi_contract();transcript();
    std::puts("Remote core: framing, transcript, UDP path, lease, watchdog, isolation and fail-closed ABI passed");
}
