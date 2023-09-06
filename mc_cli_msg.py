import socket
import os
import sys
import argparse
import tempfile
import glob
XDG_RUNTIME_DIR = "XDG_RUNTIME_DIR"
SOCKET_NAME = "mc-cli-ipc"


class MessageFormatException(Exception):
    def __init__(self, message):
        self.message = message


class IPCIOError(Exception):
    def __init__(self, message):
        self.message = message


def getIPCSocketDir():
    xdgRuntimeDir = os.environ.get(XDG_RUNTIME_DIR)
    return xdgRuntimeDir if xdgRuntimeDir else tempfile.gettempdir()


def getIPCSocketPath(pid=None, username=None, ip=None):
    ipcSocketDir = getIPCSocketDir()
    if pid:
        return os.path.join(ipcSocketDir, f"{SOCKET_NAME}-{pid}.sock")
    hasMatchConditions = any((username, ip))
    for path in glob.iglob(f"{ipcSocketDir}/{SOCKET_NAME}-*.sock"):
        ipcSocket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            ipcSocket.connect(path)
            if not hasMatchConditions:
                pingSuccess, _ = sendAndReceiveMessage(ipcSocket, ["ping"])
                if pingSuccess:
                    return path
                continue
            usernameSuccess, usernameResponse = sendAndReceiveMessage(ipcSocket, ["get-username"])
            ipSuccess, ipResponse = sendAndReceiveMessage(ipcSocket, ["get-server-ip"])
            usernameMatched = not username or (usernameSuccess and usernameResponse == username)
            ipMatched = not ip or (ipSuccess and ipResponse == ip)
            if usernameMatched and ipMatched:
                return path
        except Exception:
            pass
        finally:
            ipcSocket.close()
    return None


def sendMessage(ipcSocket, message):
    messageLength = len(message)
    sendVarInt(ipcSocket, messageLength)
    for string in message:
        sendString(ipcSocket, string)


def sendResponse(ipcSocket, response):
    success, string = response
    successInt = 0x1 if success else 0x0
    sendVarInt(ipcSocket, successInt)
    sendString(ipcSocket, string)


def sendString(ipcSocket, string):
    stringBytes = string.encode()
    stringBytesLen = len(stringBytes)
    sendVarInt(ipcSocket, stringBytesLen)
    sendall(ipcSocket, stringBytes)


def sendVarInt(ipcSocket, uInt):
    varIntBytes = bytearray()
    continuationBit = 0x80
    while continuationBit:
        lowSeven = uInt & 0x7F
        uInt >>= 7
        continuationBit = continuationBit if uInt else 0
        varIntByte = continuationBit | lowSeven
        varIntBytes.append(varIntByte)
    sendall(ipcSocket, varIntBytes)


def readMessage(ipcSocket):
    numStrings = readVarInt(ipcSocket)
    return [readString(ipcSocket) for _ in range(numStrings)]


def readResponse(ipcSocket):
    return (bool(readVarInt(ipcSocket)), readString(ipcSocket))


def readString(ipcSocket):
    stringSize = readVarInt(ipcSocket)
    return recvall(ipcSocket, stringSize).decode()


def readVarInt(ipcSocket):
    continuationBit = 0x80
    varInt = 0
    bytesProcessed = 0
    while continuationBit:
        byte = recvall(ipcSocket, 1)[0]
        continuationBit = byte & continuationBit
        byte = byte & 0x7F
        varInt |= byte << (7 * bytesProcessed)
        bytesProcessed += 1
    return varInt


def sendall(ipcSocket, toSend):
    ipcSocket.sendall(toSend)


def recvall(ipcSocket, toRead):
    data = bytearray(toRead)
    view = memoryview(data)
    while toRead:
        bytesRead = ipcSocket.recv_into(view, toRead)
        if not bytesRead:
            raise IPCIOError("connection closed unexpectedly while reading")
        view = view[bytesRead:]
        toRead -= bytesRead
    return data

def sendAndReceiveMessage(ipcSocket, message):
    sendMessage(ipcSocket, message)
    return readResponse(ipcSocket)

PROGRAM_NAME = "mc-cli"
VERSION = f"{PROGRAM_NAME} 0.0.1"
parser = argparse.ArgumentParser(
    prog=PROGRAM_NAME, description="IPC message client for mc-cli"
)
parser.add_argument(
    "-v",
    "--version",
    action="version",
    version=VERSION,
    help="show version number and exit",
)
parser.add_argument("-q", "--quiet", action="store_true", help="be quiet")
parser.add_argument("-s", "--socket", help="use alternative IPC socket path")
parser.add_argument("-p", "--pid", type=int, help="pid of the Minecraft instance")
parser.add_argument("-u", "--by-username", nargs=1, dest="username", help="username associated with the Minecraft instance")
parser.add_argument("-i", "--by-ip", nargs=1, dest="ip", help="server ip the Minecraft instance is connected to")
parser.add_argument("message", nargs="+")
args = parser.parse_args()

pid = args.pid
username = args.username[0] if args.username else None
ip = args.ip[0] if args.ip else None
unixSocketPath = args.socket if args.socket else getIPCSocketPath(args.pid, username, ip)
if not unixSocketPath:
    print(f"could not find a matching client", file=sys.stderr)
    sys.exit(-1)
ipcSocket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
try:
    ipcSocket.connect(unixSocketPath)
    success, response = sendAndReceiveMessage(ipcSocket, args.message)
    if not args.quiet:
        print(response)
    sys.exit(0 if success else -1)
except FileNotFoundError:
    print(f"path: {unixSocketPath}, was not a valid file", file=sys.stderr)
except PermissionError:
    print(
        f"insufficient permissions to open file: {unixSocketPath}", file=sys.stderr)
except IPCIOError as ipcIOError:
    print(f"{ipcIOError.message}")
except Exception as exception:
    print(f"failed to send message", file=sys.stderr)
finally:
    ipcSocket.close()
sys.exit(-1)
