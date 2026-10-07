/* Diagnostic-owned ALSA connection: exercise valid and historical oversized IPC. */
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
static int request(int socket, unsigned char code, int32_t length, const void *data) {
    unsigned char header[5]; header[0]=code; memcpy(header+1,&length,4);
    if (write(socket,header,5)!=5) return 0;
    return !data || write(socket,data,length)==length;
}
int main(int argc,char **argv) {
    if (argc!=2) return 2;
    int socket_fd=socket(AF_UNIX,SOCK_STREAM,0);
    struct timeval timeout={3,0};
    setsockopt(socket_fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    struct sockaddr_un address={.sun_family=AF_UNIX};
    if (strlen(argv[1])>=sizeof(address.sun_path)) return 3;
    strcpy(address.sun_path,argv[1]);
    if (connect(socket_fd,(void*)&address,sizeof(address))) { perror("connect"); return 4; }
    unsigned char prepare[10]={2,3}; int32_t rate=48000,frames=768;
    memcpy(prepare+2,&rate,4); memcpy(prepare+6,&frames,4);
    if (!request(socket_fd,4,10,prepare)) return 5;
    unsigned char ack=255,control[CMSG_SPACE(sizeof(int))];
    struct iovec iov={&ack,1};
    struct msghdr message={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
    if (recvmsg(socket_fd,&message,0)!=1 || ack!=0) return 6;
    struct cmsghdr *cmsg=CMSG_FIRSTHDR(&message);
    if (!cmsg || cmsg->cmsg_type!=SCM_RIGHTS) return 7;
    int memory_fd; memcpy(&memory_fd,CMSG_DATA(cmsg),sizeof(memory_fd)); close(memory_fd);
    if (!request(socket_fd,5,6144,NULL) || read(socket_fd,&ack,1)!=1 || ack!=1) return 8;
    puts("Valid shared write: accepted 6144 bytes");
    if (!request(socket_fd,5,7200,NULL)) return 9;
    int result=read(socket_fd,&ack,1);
    printf("Oversized shared write: result=%d errno=%d (expect EOF or connection reset)\n",result,errno);
    close(socket_fd);
    return result==0 || (result<0 && errno==ECONNRESET) ? 0 : 10;
}
