from flask import Flask, request, jsonify

app = Flask(__name__)

# root
@app.route("/")
def index():
    """
    this is a root dir of my server
    :return: str
    """
    return "This is root!!!!"

# GET
@app.route('/users/<user>')
def hello_user(user):
    """
    this serves as a demo purpose
    :param user:
    :return: str
    """
    return "Hello %s!" % user

# POST
@app.route('/api/post_some_data', methods=['POST'])
def get_text_prediction():
    """
    predicts requested text whether it is ham or spam
    :return: json
    """
    json = request.get_json(force=True)
    print('content: {}'.format(json))
    if len(json['text']) == 0:
        print('could not read content :(')
        return jsonify({'error': 'invalid input'})

    return jsonify({'you sent this': json['text']})
    
# running web app in local machine
if __name__ == '__main__':
    app.run(ssl_context='adhoc', port=8888)
